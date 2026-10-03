package com.samyak.simplerenderer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

@Service
final class RenderService {
    private final Path stagingDirectory;
    private final long maxInputBytes;
    private final long maxOutputBytes;
    private final long maxRows;
    private final Semaphore permits;

    RenderService(@Value("${renderer.staging-directory:${java.io.tmpdir}/simple-renderer}") Path stagingDirectory,
                  @Value("${renderer.max-input-bytes:104857600}") long maxInputBytes,
                  @Value("${renderer.max-output-bytes:104857600}") long maxOutputBytes,
                  @Value("${renderer.max-rows:1000000}") long maxRows,
                  @Value("${renderer.max-concurrent-reports:2}") int maxConcurrentReports) throws IOException {
        if (maxInputBytes < 1 || maxOutputBytes < 1 || maxRows < 1 || maxConcurrentReports < 1) {
            throw new IllegalArgumentException("Renderer limits must be positive.");
        }
        this.stagingDirectory = stagingDirectory;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputBytes = maxOutputBytes;
        this.maxRows = maxRows;
        this.permits = new Semaphore(maxConcurrentReports);
        Files.createDirectories(stagingDirectory);
    }

    Artifact render(InputStream input) throws IOException {
        if (!permits.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Renderer is busy; retry later.");
        Path path = null;
        try {
            path = Files.createTempFile(stagingDirectory, "report-", ".tmp");
            ReportMetadata metadata;
            try (var reader = new StreamingReportReader(new LimitedInput(input, maxInputBytes));
                 var output = new LimitedOutput(Files.newOutputStream(path), maxOutputBytes)) {
                metadata = reader.readMetadata();
                try (var writer = ReportWriter.open(metadata, output)) {
                    long count = 0;
                    List<Object> row;
                    while ((row = reader.nextRow(metadata)) != null) {
                        if (++count > maxRows) throw tooLarge();
                        writer.append(row);
                    }
                    reader.finish();
                    writer.finish();
                }
            }
            return new Artifact(path, metadata, permits);
        } catch (IOException | RuntimeException | Error failure) {
            try { if (path != null) Files.deleteIfExists(path); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            finally { permits.release(); }
            throw failure;
        }
    }

    // The permit includes download time: staged output still consumes capacity until close.
    static final class Artifact implements AutoCloseable {
        private final Path path;
        private final ReportMetadata metadata;
        private final Semaphore permits;
        private boolean closed;
        Artifact(Path path, ReportMetadata metadata, Semaphore permits) {
            this.path = path; this.metadata = metadata; this.permits = permits;
        }
        Path path() { return path; }
        ReportMetadata metadata() { return metadata; }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { Files.deleteIfExists(path); } finally { permits.release(); }
        }
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Report exceeds the configured size limit.");
    }

    private static final class LimitedInput extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedInput(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++count > limit) throw tooLarge();
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0 && (count += n) > limit) throw tooLarge();
            return n;
        }
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private final long limit;
        private long count;
        LimitedOutput(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int b) throws IOException {
            if (++count > limit) throw tooLarge();
            out.write(b);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len > limit - count) throw tooLarge();
            count += len;
            out.write(b, off, len);
        }
    }
}
