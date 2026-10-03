package com.samyak.simplerenderer;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class CsvReportWriter implements ReportWriter {
    private final Writer writer;

    CsvReportWriter(ReportMetadata metadata, OutputStream output) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        append(metadata.columns().stream().map(c -> (Object) c.columnName()).toList());
    }

    @Override public void append(List<Object> row) throws IOException {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) writer.write(',');
            Object value = row.get(i);
            String text = value == null ? "" : value instanceof BigDecimal d ? d.toPlainString() : value.toString();
            // Quoting escapes CSV syntax, not spreadsheet formulas. Only text is prefixed.
            if (value instanceof String && formulaLike(text)) text = "'" + text;
            writer.write('"');
            writer.write(text.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
    }

    private static boolean formulaLike(String text) {
        if (text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) return true;
        String trimmed = text.stripLeading();
        return !trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0;
    }

    @Override public void finish() throws IOException { writer.flush(); }
    @Override public void close() throws IOException { writer.close(); }
}
