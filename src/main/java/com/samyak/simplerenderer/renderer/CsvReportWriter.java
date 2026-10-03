package com.samyak.simplerenderer.renderer;

import com.samyak.simplerenderer.domain.ReportMetadata;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CsvReportWriter implements ReportWriter {
    private final Writer writer;

    public CsvReportWriter(ReportMetadata metadata, OutputStream output) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        append(metadata.columns().stream().map(c -> (Object) c.columnName()).toList());
    }

    @Override
    public void append(List<Object> row) throws IOException {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            Object value = row.get(i);
            String text;
            if (value == null) {
                text = "";
            } else if (value instanceof BigDecimal decimal) {
                text = decimal.toPlainString();
            } else {
                text = value.toString();
            }
            if (value instanceof String && formulaLike(text)) {
                text = "'" + text;
            }
            writer.write('"');
            writer.write(text.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
    }

    private static boolean formulaLike(String text) {
        if (text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) {
            return true;
        }
        String trimmed = text.stripLeading();
        return !trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0;
    }

    @Override
    public void finish() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
