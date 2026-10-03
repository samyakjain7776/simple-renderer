package com.samyak.simplerenderer.renderer;

import com.samyak.simplerenderer.domain.InvalidReportException;
import com.samyak.simplerenderer.domain.ReportMetadata;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

public final class XlsxReportWriter implements ReportWriter {
    private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
    private final Sheet sheet;
    private final CellStyle[] styles;
    private final OutputStream output;
    private int rowIndex = 1;

    public XlsxReportWriter(ReportMetadata metadata, OutputStream output) {
        this.output = output;
        workbook.setCompressTempFiles(true);
        sheet = workbook.createSheet("Report");
        styles = new CellStyle[metadata.columns().size()];
        var cache = new HashMap<String, CellStyle>();
        Row header = sheet.createRow(0);
        for (int i = 0; i < styles.length; i++) {
            var column = metadata.columns().get(i);
            header.createCell(i).setCellValue(column.columnName());
            sheet.setColumnWidth(i, 24 * 256);
            styles[i] = cache.computeIfAbsent(column.excelFormat(), format -> {
                CellStyle style = workbook.createCellStyle();
                style.setDataFormat(workbook.createDataFormat().getFormat(format));
                return style;
            });
        }
    }

    @Override
    public void append(List<Object> values) {
        if (rowIndex >= 1_048_576) {
            throw new InvalidReportException("XLSX worksheet row limit exceeded.");
        }
        Row row = sheet.createRow(rowIndex++);
        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            Object value = values.get(i);
            cell.setCellStyle(styles[i]);
            if (value == null) {
                continue;
            }
            if (value instanceof BigDecimal number) {
                if (number.precision() > 15) {
                    cell.setCellValue(number.toPlainString());
                } else {
                    cell.setCellValue(number.doubleValue());
                }
            } else if (value instanceof LocalDate date) {
                cell.setCellValue(date);
            } else if (value instanceof Boolean bool) {
                cell.setCellValue(bool);
            } else {
                cell.setCellValue(value.toString());
            }
        }
    }

    @Override
    public void finish() throws IOException {
        workbook.write(output);
    }

    @Override
    public void close() throws IOException {
        try (workbook) {
            ((SXSSFSheet) sheet).flushRows();
        }
    }
}
