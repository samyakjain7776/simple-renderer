package com.samyak.simplerenderer.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonToken;
import com.samyak.simplerenderer.parser.StreamingReportReader.RawCell;
import com.samyak.simplerenderer.parser.StreamingReportReader.RawRow;
import com.samyak.simplerenderer.renderer.ReportWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderedChunkPipelineTest {
  @Test
  void slowFirstChunkKeepsCompletedChunksBoundedAndWritesInOrder() throws Exception {
    var releaseFirst = new CountDownLatch(1);
    var laterCompleted = new CountDownLatch(2);
    var read = new AtomicInteger();
    var writer = new RecordingWriter();
    try (var workers = Executors.newFixedThreadPool(3);
        var coordinator = Executors.newSingleThreadExecutor()) {
      Future<?> job =
          coordinator.submit(
              () -> {
                new OrderedChunkPipeline(2, 1000, 3, 100)
                    .render(
                        () -> {
                          int index = read.getAndIncrement();
                          if (index == 12) return null;
                          if (releaseFirst.getCount() > 0 && index >= 6)
                            fail("Read past the three admitted chunks");
                          return row(index, "row-" + index);
                        },
                        rows -> {
                          if (rows.getFirst().rowNumber() == 0) {
                            try {
                              if (!releaseFirst.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("First chunk was never released");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new CancellationException();
                            }
                          } else if (rows.getFirst().rowNumber() < 6) laterCompleted.countDown();
                          return values(rows);
                        },
                        writer,
                        workers);
                return null;
              });
      try {
        assertTrue(laterCompleted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> job.get(150, TimeUnit.MILLISECONDS));
        assertEquals(6, read.get());
        assertTrue(writer.rows.isEmpty(), "Later chunks must not be written ahead of the first");
      } finally {
        releaseFirst.countDown();
      }
      job.get(5, TimeUnit.SECONDS);
      assertEquals(12, writer.rows.size());
      for (int i = 0; i < 12; i++) assertEquals("row-" + i, writer.rows.get(i).getFirst());
    }
  }

  @Test
  void characterTargetSplitsWideRowsBeforeTheRowLimit() throws Exception {
    var read = new AtomicInteger();
    var sizes = new CopyOnWriteArrayList<Integer>();
    try (var workers = Executors.newFixedThreadPool(2)) {
      new OrderedChunkPipeline(500, 10, 2, 100)
          .render(
              () -> {
                int index = read.getAndIncrement();
                return index == 5 ? null : row(index, "abcdef");
              },
              rows -> {
                sizes.add(rows.size());
                return values(rows);
              },
              new RecordingWriter(),
              workers);
    }
    Collections.sort(sizes);
    assertEquals(List.of(1, 2, 2), sizes);
  }

  @Test
  void rowLimitStillBoundsNullCellsWithNoCharacters() throws Exception {
    var read = new AtomicInteger();
    var sizes = new CopyOnWriteArrayList<Integer>();
    try (var workers = Executors.newFixedThreadPool(2)) {
      new OrderedChunkPipeline(2, 10, 2, 100)
          .render(
              () -> {
                int index = read.getAndIncrement();
                return index == 5
                    ? null
                    : new RawRow(index, List.of(new RawCell(JsonToken.VALUE_NULL, null)));
              },
              rows -> {
                sizes.add(rows.size());
                return values(rows);
              },
              new RecordingWriter(),
              workers);
    }
    Collections.sort(sizes);
    assertEquals(List.of(1, 2, 2), sizes);
  }

  private static RawRow row(int index, String text) {
    return new RawRow(index, List.of(new RawCell(JsonToken.VALUE_STRING, text)));
  }

  private static List<List<Object>> values(List<RawRow> rows) {
    return rows.stream()
        .map(row -> Collections.<Object>singletonList(row.cells().getFirst().text()))
        .toList();
  }

  private static final class RecordingWriter implements ReportWriter {
    final List<List<Object>> rows = new CopyOnWriteArrayList<>();

    public void append(List<Object> row) {
      rows.add(row);
    }

    public void finish() {}

    public void close() {}
  }
}
