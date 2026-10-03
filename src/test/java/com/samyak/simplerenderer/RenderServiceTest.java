package com.samyak.simplerenderer;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RenderServiceTest {
    @TempDir Path directory;

    private RenderService service() throws IOException {
        return new RenderService(directory, 10_000_000, 10_000_000, 100_000, 1);
    }

    static String report(String type, String columns, String rows) {
        return "{\"metadata\":{\"name\":\"Test\",\"fileType\":\"" + type
                + "\",\"columns\":" + columns + "},\"values\":" + rows + "}";
    }

    static final String TEXT_COLUMN = "[{\"columnName\":\"Text\",\"dataType\":\"STRING\"}]";
    static InputStream input(String json) { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }

    @Test void csvEscapesUnicodeQuotesNewlinesAndFormulaText() throws Exception {
        String json = report("csv", TEXT_COLUMN, "[[\"a,\\\"b\\\"\\n雪\"],[\"=1+1\"],[null],[\"\"]]");
        Path path;
        try (var artifact = service().render(input(json))) {
            path = artifact.path();
            assertEquals("\"Text\"\r\n\"a,\"\"b\"\"\n雪\"\r\n\"'=1+1\"\r\n\"\"\r\n\"\"\r\n", Files.readString(path));
        }
        assertFalse(Files.exists(path));
    }

    @Test void xlsxPreservesTypesPrecisionFormatsAndOrderBeyondRowWindow() throws Exception {
        String columns = """
                [{"columnName":"ID","dataType":"STRING"},
                 {"columnName":"Amount","dataType":"DECIMAL","format":"#,##0.00"},
                 {"columnName":"Date","dataType":"DATE"},
                 {"columnName":"Active","dataType":"BOOLEAN"}]
                """;
        var rows = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) rows.append(',');
            rows.append("[\"=").append(i).append("\",\"-123.50\",\"2026-01-01\",true]");
        }
        rows.append(",[\"precise\",\"1234567890123456.78\",null,false]]");
        try (var artifact = service().render(input(report("xlsx", columns, rows.toString())));
             var workbook = WorkbookFactory.create(artifact.path().toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(301, sheet.getLastRowNum());
            for (int i = 0; i < 300; i++) assertEquals("=" + i, sheet.getRow(i + 1).getCell(0).getStringCellValue());
            Row first = sheet.getRow(1);
            assertEquals(CellType.STRING, first.getCell(0).getCellType());
            assertEquals(-123.50, first.getCell(1).getNumericCellValue());
            assertEquals("#,##0.00", first.getCell(1).getCellStyle().getDataFormatString());
            assertEquals("2026-01-01", first.getCell(2).getLocalDateTimeCellValue().toLocalDate().toString());
            assertTrue(first.getCell(3).getBooleanCellValue());
            assertEquals("1234567890123456.78", sheet.getRow(301).getCell(1).getStringCellValue());
            assertTrue(workbook.getNumCellStyles() < 10);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"csv", "xlsx"})
    void emptyReportsHaveHeaders(String type) throws Exception {
        try (var artifact = service().render(input(report(type, TEXT_COLUMN, "[]")))) {
            assertTrue(Files.size(artifact.path()) > 0);
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"values\":[],\"metadata\":{}}",
            "{\"metadata\":{\"name\":\"Test\",\"name\":\"Again\"},\"values\":[]}"
    }) void rejectsInvalidRootAndDuplicates(String json) throws Exception {
        assertThrows(Exception.class, () -> service().render(input(json)));
        assertEmptyDirectory();
    }

    @ParameterizedTest @ValueSource(strings = {"[[1]]", "[[true]]", "[[{}]]", "[[]]", "[[\"a\",\"b\"]]", "[[\"a\"]"})
    void invalidRowsAreRejectedAndCleaned(String rows) throws Exception {
        var service = service();
        assertThrows(Exception.class, () -> service.render(input(report("xlsx", TEXT_COLUMN, rows))));
        assertEmptyDirectory();
        try (var artifact = service.render(input(report("csv", TEXT_COLUMN, "[]")))) {
            assertTrue(Files.exists(artifact.path()));
        }
    }

    @Test void validatesEndOfDocumentBeforePublishing() throws Exception {
        assertThrows(InvalidReportException.class, () -> service().render(input(report("csv", TEXT_COLUMN, "[]") + "{}")));
        assertEmptyDirectory();
    }

    @ParameterizedTest @ValueSource(strings = {"1e999999999", "NaN", "Infinity"})
    void rejectsUnboundedDecimals(String value) throws Exception {
        String columns = "[{\"columnName\":\"Amount\",\"dataType\":\"DECIMAL\"}]";
        assertThrows(InvalidReportException.class, () -> service().render(input(report("csv", columns, "[[\"" + value + "\"]]"))));
        assertEmptyDirectory();
    }

    @Test void admissionPermitRemainsHeldUntilDownloadCloses() throws Exception {
        var service = service();
        try (var first = service.render(input(report("csv", TEXT_COLUMN, "[]")))) {
            var error = assertThrows(ResponseStatusException.class, () -> service.render(input(report("csv", TEXT_COLUMN, "[]"))));
            assertEquals(429, error.getStatusCode().value());
        }
        try (var second = service.render(input(report("csv", TEXT_COLUMN, "[]")))) {
            assertTrue(Files.exists(second.path()));
        }
        assertEmptyDirectory();
    }

    @Test void byteAndRowLimitsRejectAndCleanOutput() throws Exception {
        String json = report("csv", TEXT_COLUMN, "[[\"hello\"],[\"world\"]]");
        for (var service : new RenderService[] {
                new RenderService(directory, 20, 10_000, 100, 1),
                new RenderService(directory, 10_000, 5, 100, 1),
                new RenderService(directory, 10_000, 10_000, 1, 1)}) {
            var error = assertThrows(ResponseStatusException.class, () -> service.render(input(json)));
            assertEquals(413, error.getStatusCode().value());
            assertEmptyDirectory();
        }
    }

    @Test void readerReturnsFirstRowWithoutConsumingTheWholeInput() throws Exception {
        String json = report("csv", TEXT_COLUMN, "[[\"first\"]," + "[\"later\"],".repeat(10_000) + "[\"last\"]]");
        AtomicInteger consumed = new AtomicInteger();
        InputStream counted = new FilterInputStream(input(json)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len); if (n > 0) consumed.addAndGet(n); return n;
            }
        };
        try (var reader = new StreamingReportReader(counted)) {
            var metadata = reader.readMetadata();
            assertEquals("first", reader.nextRow(metadata).getFirst());
            assertTrue(consumed.get() < json.length() / 2, "Parser should only read ahead a bounded buffer");
        }
    }

    @Test void poiTemporaryFilesAreRemovedAfterSuccessAndFailure() throws Exception {
        Path poiDirectory = Files.createDirectory(directory.resolve("poi"));
        var strategy = new org.apache.poi.util.DefaultTempFileCreationStrategy(poiDirectory.toFile());
        org.apache.poi.util.TempFile.setTempFileCreationStrategy(strategy);
        try {
            try (var artifact = service().render(input(report("xlsx", TEXT_COLUMN, "[[\"ok\"]]")))) {
                assertTrue(Files.size(artifact.path()) > 0);
            }
            assertThrows(Exception.class, () -> service().render(input(report("xlsx", TEXT_COLUMN, "[[1]]"))));
            try (var files = Files.walk(poiDirectory)) {
                assertEquals(0, files.filter(Files::isRegularFile).count());
            }
        } finally {
            org.apache.poi.util.TempFile.setTempFileCreationStrategy(new org.apache.poi.util.DefaultTempFileCreationStrategy());
        }
    }

    private void assertEmptyDirectory() throws IOException {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
