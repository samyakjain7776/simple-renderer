package com.samyak.simplerenderer.renderer;

import com.samyak.simplerenderer.domain.ReportMetadata;
import java.io.IOException;
import java.io.OutputStream;

public final class ReportWriterFactory {
    private ReportWriterFactory() {
    }

    public static ReportWriter create(ReportMetadata metadata, OutputStream output) throws IOException {
        return switch (metadata.fileType()) {
            case "csv" -> new CsvReportWriter(metadata, output);
            case "xlsx" -> new XlsxReportWriter(metadata, output);
            default -> throw new IllegalArgumentException("Unsupported report type: " + metadata.fileType());
        };
    }
}
