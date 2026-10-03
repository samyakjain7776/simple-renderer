package com.samyak.simplerenderer;

import com.samyak.simplerenderer.domain.InvalidReportException;
import com.samyak.simplerenderer.parser.StreamingReportReader;
import com.samyak.simplerenderer.service.RenderService;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.samyak.simplerenderer.RenderServiceTest.input;
import static org.junit.jupiter.api.Assertions.*;

class SchemaCompatibilityTest {
    @TempDir Path directory;

    private static String documentReport(long count) {
        return """
            {
              "metadata": {
                "name": "Daily_Risk_Report",
                "fileType": "xlsx",
                "rowCount": %d,
                "columns": [
                  {"columnName":"Trade_ID","dataType":"STRING","format":"General"},
                  {"columnName":"Notional_Value","dataType":"DECIMAL","format":"#,##0.00"},
                  {"columnName":"Settlement_Date","dataType":"DATE","format":"yyyy-MM-dd"}
                ]
              },
              "values": [
                ["TRD-123","1500000.50","2023-10-01"],
                ["TRD-124","250000.00","2023-10-02"]
              ]
            }
            """.formatted(count);
    }

    private RenderService service() throws Exception {
        return new RenderService(directory, 1_000_000, 1_000_000, 100_000, 1);
    }

    @Test void acceptsRowCountAndColumnFormats() throws Exception {
        try (var reader = new StreamingReportReader(input(documentReport(100000)))) {
            var metadata = reader.readMetadata();
            assertEquals(100000L, metadata.rowCount());
            assertEquals("General", metadata.columns().getFirst().format());
            assertEquals("yyyy-MM-dd", metadata.columns().get(2).format());
            assertEquals("TRD-123", reader.nextRow(metadata).getFirst());
        }
    }

    @Test void rendersXlsxWithMatchingRowCount() throws Exception {
        try (var artifact = service().render(input(documentReport(2)));
             var workbook = WorkbookFactory.create(artifact.path().toFile())) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum());
            assertEquals("TRD-124", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(1500000.50, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals("2023-10-01", sheet.getRow(1).getCell(2).getLocalDateTimeCellValue().toLocalDate().toString());
            assertEquals("yyyy-mm-dd", sheet.getRow(1).getCell(2).getCellStyle().getDataFormatString());
        }
    }

    @ParameterizedTest @ValueSource(longs = {0, 1, 3, 100000})
    void mismatchedCountsFailAndReleaseResources(long count) throws Exception {
        var service = service();
        assertThrows(InvalidReportException.class, () -> service.render(input(documentReport(count))));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        try (var artifact = service.render(input(documentReport(2)))) {
            assertTrue(Files.size(artifact.path()) > 0);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "1.5"})
    void invalidCountValuesAreRejected(String count) throws Exception {
        String json = documentReport(2).replace("\"rowCount\": 2", "\"rowCount\": " + count);
        assertThrows(com.fasterxml.jackson.core.JacksonException.class, () -> service().render(input(json)));
    }

    @Test void zeroCountAcceptsAnEmptyReport() throws Exception {
        String json = RenderServiceTest.report("csv", RenderServiceTest.TEXT_COLUMN, "[]")
                .replace("\"name\":", "\"rowCount\":0,\"name\":");
        try (var artifact = service().render(input(json))) {
            assertEquals("\"Text\"\r\n", Files.readString(artifact.path()));
        }
    }
}
