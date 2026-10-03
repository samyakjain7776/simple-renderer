package com.samyak.simplerenderer.service;

import com.samyak.simplerenderer.parser.StreamingReportReader.RawRow;
import com.samyak.simplerenderer.renderer.ReportWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Converts bounded chunks in parallel and writes results in input order. */
final class OrderedChunkPipeline {
    @FunctionalInterface
    interface RowSource {
        RawRow next() throws IOException;
    }

    private final int chunkSize;
    private final int maxChunkCharacters;
    private final int maxInFlight;
    private final long maxRows;

    OrderedChunkPipeline(int chunkSize, int maxChunkCharacters, int maxInFlight, long maxRows) {
        if (chunkSize < 1 || maxChunkCharacters < 1 || maxInFlight < 1 || maxRows < 1) {
            throw new IllegalArgumentException("Pipeline limits must be positive.");
        }
        this.chunkSize = chunkSize;
        this.maxChunkCharacters = maxChunkCharacters;
        this.maxInFlight = maxInFlight;
        this.maxRows = maxRows;
    }

    void render(RowSource source, Function<List<RawRow>, List<List<Object>>> transform,
                ReportWriter writer, ExecutorService workers) throws IOException {
        var pending = new TreeMap<Long, Future<List<List<Object>>>>();
        long nextSequence = 0, nextToWrite = 0, rowCount = 0;
        boolean endOfInput = false;
        try {
            while (!endOfInput || !pending.isEmpty()) {
                while (!endOfInput && pending.size() < maxInFlight) {
                    var rows = new ArrayList<RawRow>();
                    long characters = 0;
                    while (rows.size() < chunkSize && characters < maxChunkCharacters) {
                        RawRow row = source.next();
                        if (row == null) {
                            endOfInput = true;
                            break;
                        }
                        if (++rowCount > maxRows) {
                            throw new ResponseStatusException(
                                    HttpStatus.PAYLOAD_TOO_LARGE,
                                    "Report exceeds the configured row limit.");
                        }
                        rows.add(row);
                        for (var cell : row.cells()) {
                            if (cell.text() != null) characters += cell.text().length();
                        }
                    }
                    // One row can cross the target. The parser caps rows at 128 Ki characters.
                    if (!rows.isEmpty()) pending.put(nextSequence++, workers.submit(() -> transform.apply(rows)));
                }
                if (pending.isEmpty()) {
                    break;
                }
                var completed = await(pending.get(nextToWrite));
                for (var row : completed) writer.append(row);
                // Release the slot only after writing, not when the worker finishes.
                pending.remove(nextToWrite++);
            }
        } finally {
            for (var future : pending.values()) future.cancel(true);
        }
    }

    private static List<List<Object>> await(Future<List<List<Object>>> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Report rendering was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Report chunk processing failed.", cause);
        }
    }
}
