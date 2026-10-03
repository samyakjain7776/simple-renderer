package com.samyak.simplerenderer.service;

import com.samyak.simplerenderer.config.RendererProperties;
import com.samyak.simplerenderer.domain.ReportMetadata;
import com.samyak.simplerenderer.parser.ReportRowTransformer;
import com.samyak.simplerenderer.parser.StreamingReportReader;
import com.samyak.simplerenderer.renderer.ReportWriter;
import com.samyak.simplerenderer.renderer.ReportWriterFactory;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public final class RenderService {
  private final Path stagingDirectory;
  private final long maxInputBytes;
  private final long maxOutputBytes;
  private final long maxRows;
  private final int workerThreads;
  private final int chunkSize;
  private final int maxInFlightChunks;
  private final int maxChunkCharacters;
  private final Semaphore permits;

  @Autowired
  public RenderService(RendererProperties properties) throws IOException {
    this(
        properties.getStagingDirectory(),
        properties.getMaxInputBytes(),
        properties.getMaxOutputBytes(),
        properties.getMaxRows(),
        properties.getMaxConcurrentReports(),
        properties.getWorkerThreads(),
        properties.getChunkSize(),
        properties.getMaxInFlightChunks(),
        properties.getMaxChunkCharacters());
  }

  public RenderService(
      Path stagingDirectory,
      long maxInputBytes,
      long maxOutputBytes,
      long maxRows,
      int maxConcurrentReports)
      throws IOException {
    this(
        stagingDirectory,
        maxInputBytes,
        maxOutputBytes,
        maxRows,
        maxConcurrentReports,
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        500,
        4);
  }

  public RenderService(
      Path stagingDirectory,
      long maxInputBytes,
      long maxOutputBytes,
      long maxRows,
      int maxConcurrentReports,
      int workerThreads,
      int chunkSize,
      int maxInFlightChunks)
      throws IOException {
    this(
        stagingDirectory,
        maxInputBytes,
        maxOutputBytes,
        maxRows,
        maxConcurrentReports,
        workerThreads,
        chunkSize,
        maxInFlightChunks,
        256 * 1024);
  }

  public RenderService(
      Path stagingDirectory,
      long maxInputBytes,
      long maxOutputBytes,
      long maxRows,
      int maxConcurrentReports,
      int workerThreads,
      int chunkSize,
      int maxInFlightChunks,
      int maxChunkCharacters)
      throws IOException {
    if (maxInputBytes < 1
        || maxOutputBytes < 1
        || maxRows < 1
        || maxConcurrentReports < 1
        || workerThreads < 1
        || chunkSize < 1
        || maxInFlightChunks < 1
        || maxChunkCharacters < 1) {
      throw new IllegalArgumentException(
          "Renderer limits and parallelism settings must be positive.");
    }
    this.stagingDirectory = stagingDirectory;
    this.maxInputBytes = maxInputBytes;
    this.maxOutputBytes = maxOutputBytes;
    this.maxRows = maxRows;
    this.workerThreads = workerThreads;
    this.chunkSize = chunkSize;
    this.maxChunkCharacters = maxChunkCharacters;
    this.maxInFlightChunks = maxInFlightChunks;
    this.permits = new Semaphore(maxConcurrentReports);
    Files.createDirectories(stagingDirectory);
  }

  public Artifact render(InputStream input) throws IOException {
    if (!permits.tryAcquire()) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Renderer is busy; retry later.");
    }
    Path path = null;
    ExecutorService workers = Executors.newFixedThreadPool(workerThreads);
    try {
      path = Files.createTempFile(stagingDirectory, "report-", ".tmp");
      ReportMetadata metadata;
      try (var reader = new StreamingReportReader(new LimitedInput(input, maxInputBytes));
          var output = new LimitedOutput(Files.newOutputStream(path), maxOutputBytes)) {
        metadata = reader.readMetadata();
        try (var writer = ReportWriterFactory.create(metadata, output)) {
          renderRows(reader, metadata, writer, workers);
          reader.finish();
          writer.finish();
        }
      }
      return new Artifact(path, metadata, permits);
    } catch (IOException | RuntimeException | Error failure) {
      workers.shutdownNow();
      try {
        if (path != null) Files.deleteIfExists(path);
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      } finally {
        permits.release();
      }
      throw failure;
    } finally {
      workers.shutdown();
    }
  }

  private void renderRows(
      StreamingReportReader reader,
      ReportMetadata metadata,
      ReportWriter writer,
      ExecutorService workers)
      throws IOException {
    var pipeline =
        new OrderedChunkPipeline(chunkSize, maxChunkCharacters, maxInFlightChunks, maxRows);
    pipeline.render(
        () -> reader.nextRawRow(metadata),
        rawRows -> {
          var rows = new ArrayList<List<Object>>(rawRows.size());
          for (var row : rawRows) {
            if (Thread.currentThread().isInterrupted())
              throw new java.util.concurrent.CancellationException();
            rows.add(ReportRowTransformer.transform(row, metadata));
          }
          return rows;
        },
        writer,
        workers);
  }

  public static final class Artifact implements AutoCloseable {
    private final Path path;
    private final ReportMetadata metadata;
    private final Semaphore permits;
    private boolean closed;

    private Artifact(Path path, ReportMetadata metadata, Semaphore permits) {
      this.path = path;
      this.metadata = metadata;
      this.permits = permits;
    }

    public Path path() {
      return path;
    }

    public ReportMetadata metadata() {
      return metadata;
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        Files.deleteIfExists(path);
      } finally {
        permits.release();
      }
    }
  }

  private static ResponseStatusException tooLarge() {
    return new ResponseStatusException(
        HttpStatus.PAYLOAD_TOO_LARGE, "Report exceeds the configured size limit.");
  }

  private static final class LimitedInput extends FilterInputStream {
    private final long limit;
    private long count;

    private LimitedInput(InputStream in, long limit) {
      super(in);
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value != -1 && ++count > limit) {
        throw tooLarge();
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      if (n > 0 && (count += n) > limit) {
        throw tooLarge();
      }
      return n;
    }
  }

  private static final class LimitedOutput extends FilterOutputStream {
    private final long limit;
    private long count;

    private LimitedOutput(java.io.OutputStream out, long limit) {
      super(out);
      this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
      if (++count > limit) {
        throw tooLarge();
      }
      out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (len > limit - count) {
        throw tooLarge();
      }
      count += len;
      out.write(b, off, len);
    }
  }
}
