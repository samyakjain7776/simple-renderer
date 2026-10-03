package com.samyak.simplerenderer.parser;

import com.fasterxml.jackson.core.JsonToken;
import com.samyak.simplerenderer.domain.InvalidReportException;
import com.samyak.simplerenderer.domain.ReportMetadata;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class ReportRowTransformer {
    private ReportRowTransformer() {
    }

    public static List<Object> transform(StreamingReportReader.RawRow rawRow, ReportMetadata metadata) {
        var values = new ArrayList<Object>(rawRow.cells().size());
        for (int i = 0; i < rawRow.cells().size(); i++) {
            var cell = rawRow.cells().get(i);
            values.add(
                    convert(
                            cell.token(),
                            cell.text(),
                            metadata.columns().get(i).dataType(),
                            rawRow.rowNumber()));
        }
        return values;
    }

    private static Object convert(
            JsonToken token, String text, ReportMetadata.DataType type, long rowNumber) {
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        try {
            return switch (type) {
                case STRING -> {
                    if (token != JsonToken.VALUE_STRING) {
                        invalid(rowNumber, "STRING requires JSON text");
                    }
                    yield text;
                }
                case DECIMAL -> {
                    if (token != JsonToken.VALUE_STRING && !token.isNumeric()) {
                        invalid(rowNumber, "DECIMAL requires a number or numeric text");
                    }
                    if (text.length() > 128) {
                        invalid(rowNumber, "decimal is too long");
                    }
                    BigDecimal number = new BigDecimal(text);
                    if (number.precision() > 100 || Math.abs((long) number.scale()) > 100) {
                        invalid(rowNumber, "decimal precision or scale exceeds 100");
                    }
                    yield number;
                }
                case DATE -> {
                    if (token != JsonToken.VALUE_STRING) {
                        invalid(rowNumber, "DATE requires ISO yyyy-MM-dd text");
                    }
                    LocalDate date = LocalDate.parse(text);
                    if (date.isBefore(LocalDate.of(1900, 1, 1))
                            || date.isAfter(LocalDate.of(9999, 12, 31))) {
                        invalid(rowNumber, "date is outside 1900–9999");
                    }
                    yield date;
                }
                case BOOLEAN -> {
                    if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) {
                        invalid(rowNumber, "BOOLEAN requires true or false");
                    }
                    yield token == JsonToken.VALUE_TRUE;
                }
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new InvalidReportException("Invalid " + type + " at row " + rowNumber + ".");
        }
    }

    private static void invalid(long rowNumber, String reason) {
        throw new InvalidReportException("Invalid row " + rowNumber + ": " + reason + ".");
    }
}
