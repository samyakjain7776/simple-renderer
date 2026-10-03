package com.samyak.simplerenderer;

import java.util.List;
import java.util.Set;

record ReportMetadata(String name, String fileType, List<Column> columns) {
    enum DataType { STRING, DECIMAL, DATE, BOOLEAN }

    record Column(String columnName, DataType dataType, String format) {
        Column {
            if (columnName == null || columnName.isBlank() || columnName.length() > 128 || dataType == null) {
                throw new InvalidReportException("Columns require a name of 1–128 characters and a supported dataType.");
            }
            Set<String> formats = switch (dataType) {
                case DECIMAL -> Set.of("0", "0.00", "#,##0.00");
                case DATE -> Set.of("yyyy-mm-dd", "mm/dd/yyyy");
                default -> Set.of();
            };
            if (format != null && !formats.contains(format)) {
                throw new InvalidReportException("Unsupported column format.");
            }
        }

        String excelFormat() {
            if (format != null) return format;
            return dataType == DataType.DATE ? "yyyy-mm-dd" : "General";
        }
    }

    ReportMetadata {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}")) {
            throw new InvalidReportException("Report name must be 1–64 letters, digits, spaces, underscores or hyphens.");
        }
        if (!"csv".equals(fileType) && !"xlsx".equals(fileType)) {
            throw new InvalidReportException("fileType must be csv or xlsx.");
        }
        if (columns == null || columns.isEmpty() || columns.size() > 128 || columns.stream().anyMatch(java.util.Objects::isNull)) {
            throw new InvalidReportException("A report requires 1–128 columns.");
        }
        columns = List.copyOf(columns);
    }

    String contentType() {
        return fileType.equals("csv") ? "text/csv;charset=UTF-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
