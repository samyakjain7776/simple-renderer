package com.samyak.simplerenderer;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Owns a forward-only parser; only metadata and the current row are materialized. */
final class StreamingReportReader implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(8).maxStringLength(32767).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private final JsonParser parser;
    private long rowNumber;

    StreamingReportReader(InputStream input) throws IOException {
        parser = MAPPER.getFactory().createParser(input);
    }

    ReportMetadata readMetadata() throws IOException {
        expect(parser.nextToken(), JsonToken.START_OBJECT);
        expectField("metadata");
        expect(parser.nextToken(), JsonToken.START_OBJECT);
        // Cap metadata before record binding; the main values array is never tree-bound.
        var buffer = new StringBuilder();
        int depth = 1;
        try (var out = new java.io.StringWriter(); var generator = MAPPER.getFactory().createGenerator(out)) {
            generator.copyCurrentEvent(parser);
            while (depth > 0) {
                JsonToken token = parser.nextToken();
                if (token == null) throw new InvalidReportException("Incomplete metadata.");
                if (token.isStructStart()) depth++;
                if (token.isStructEnd()) depth--;
                generator.copyCurrentEvent(parser);
                generator.flush();
                if (out.getBuffer().length() > 65536) throw new InvalidReportException("Metadata exceeds 64 Ki characters.");
            }
            buffer.append(out);
        }
        ReportMetadata metadata = MAPPER.readValue(buffer.toString(), ReportMetadata.class);
        expectField("values");
        expect(parser.nextToken(), JsonToken.START_ARRAY);
        return metadata;
    }

    List<Object> nextRow(ReportMetadata metadata) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_ARRAY) {
            if (metadata.rowCount() != null && rowNumber != metadata.rowCount()) {
                throw new InvalidReportException("rowCount does not match the number of data rows.");
            }
            return null;
        }
        expect(token, JsonToken.START_ARRAY);
        rowNumber++;
        if (metadata.rowCount() != null && rowNumber > metadata.rowCount()) {
            throw new InvalidReportException("Data contains more rows than rowCount.");
        }
        var row = new ArrayList<Object>(metadata.columns().size());
        int characters = 0;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (row.size() >= metadata.columns().size()) invalidCell("too many columns");
            if (token == null || !token.isScalarValue()) invalidCell("expected a scalar cell");
            String text = token == JsonToken.VALUE_NULL ? null : parser.getText();
            if (text != null) {
                characters += text.length();
                if (text.length() > 32767 || characters > 131072) invalidCell("cell or row length limit exceeded");
            }
            row.add(convert(token, text, metadata.columns().get(row.size()).dataType()));
        }
        if (row.size() != metadata.columns().size()) invalidCell("column count does not match metadata");
        return row;
    }

    void finish() throws IOException {
        expect(parser.nextToken(), JsonToken.END_OBJECT);
        if (parser.nextToken() != null) throw new InvalidReportException("Trailing JSON is not allowed.");
    }

    private Object convert(JsonToken token, String text, ReportMetadata.DataType type) {
        if (token == JsonToken.VALUE_NULL) return null;
        try {
            return switch (type) {
                case STRING -> {
                    if (token != JsonToken.VALUE_STRING) invalidCell("STRING requires JSON text");
                    yield text;
                }
                case DECIMAL -> {
                    if (token != JsonToken.VALUE_STRING && !token.isNumeric()) invalidCell("DECIMAL requires a number or numeric text");
                    if (text.length() > 128) invalidCell("decimal is too long");
                    BigDecimal number = new BigDecimal(text);
                    if (number.precision() > 100 || Math.abs((long) number.scale()) > 100) invalidCell("decimal precision or scale exceeds 100");
                    yield number;
                }
                case DATE -> {
                    if (token != JsonToken.VALUE_STRING) invalidCell("DATE requires ISO yyyy-MM-dd text");
                    LocalDate date = LocalDate.parse(text);
                    if (date.isBefore(LocalDate.of(1900, 1, 1)) || date.isAfter(LocalDate.of(9999, 12, 31))) invalidCell("date is outside 1900–9999");
                    yield date;
                }
                case BOOLEAN -> {
                    if (token != JsonToken.VALUE_TRUE && token != JsonToken.VALUE_FALSE) invalidCell("BOOLEAN requires true or false");
                    yield token == JsonToken.VALUE_TRUE;
                }
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new InvalidReportException("Invalid " + type + " at row " + rowNumber + ".");
        }
    }

    private void invalidCell(String reason) {
        throw new InvalidReportException("Invalid row " + rowNumber + ": " + reason + ".");
    }

    private void expectField(String name) throws IOException {
        expect(parser.nextToken(), JsonToken.FIELD_NAME);
        if (!name.equals(parser.currentName())) throw new InvalidReportException("Expected field: " + name + ".");
    }

    private static void expect(JsonToken actual, JsonToken expected) {
        if (actual != expected) throw new InvalidReportException("Invalid report structure; expected " + expected + ".");
    }

    @Override public void close() throws IOException { parser.close(); }
}
