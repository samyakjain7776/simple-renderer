package com.samyak.simplerenderer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

interface ReportWriter extends AutoCloseable {
    void append(List<Object> row) throws IOException;
    void finish() throws IOException;
    @Override void close() throws IOException;

    static ReportWriter open(ReportMetadata metadata, OutputStream output) throws IOException {
        return metadata.fileType().equals("csv") ? new CsvReportWriter(metadata, output)
                : new XlsxReportWriter(metadata, output);
    }
}
