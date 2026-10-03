package com.samyak.simplerenderer.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samyak.simplerenderer.domain.InvalidReportException;
import com.samyak.simplerenderer.domain.ReportMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class StreamingReportReader implements AutoCloseable {
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(32767)
                          .maxNumberLength(128)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  private final JsonParser parser;
  private long rowNumber;

  public record RawCell(JsonToken token, String text) {}

  public record RawRow(long rowNumber, List<RawCell> cells) {}

  public StreamingReportReader(InputStream input) throws IOException {
    parser = MAPPER.getFactory().createParser(input);
  }

  public ReportMetadata readMetadata() throws IOException {
    expect(parser.nextToken(), JsonToken.START_OBJECT);
    expectField("metadata");
    expect(parser.nextToken(), JsonToken.START_OBJECT);
    var buffer = new StringBuilder();
    int depth = 1;
    try (var out = new java.io.StringWriter();
        var generator = MAPPER.getFactory().createGenerator(out)) {
      generator.copyCurrentEvent(parser);
      while (depth > 0) {
        JsonToken token = parser.nextToken();
        if (token == null) {
          throw new InvalidReportException("Incomplete metadata.");
        }
        if (token.isStructStart()) depth++;
        if (token.isStructEnd()) depth--;
        generator.copyCurrentEvent(parser);
        generator.flush();
        if (out.getBuffer().length() > 65536)
          throw new InvalidReportException("Metadata exceeds 64 Ki characters.");
      }
      buffer.append(out);
    }
    ReportMetadata metadata = MAPPER.readValue(buffer.toString(), ReportMetadata.class);
    expectField("values");
    expect(parser.nextToken(), JsonToken.START_ARRAY);
    return metadata;
  }

  public List<Object> nextRow(ReportMetadata metadata) throws IOException {
    RawRow rawRow = nextRawRow(metadata);
    return rawRow == null ? null : ReportRowTransformer.transform(rawRow, metadata);
  }

  public RawRow nextRawRow(ReportMetadata metadata) throws IOException {
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
    var cells = new ArrayList<RawCell>(metadata.columns().size());
    int characters = 0;
    while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
      if (cells.size() >= metadata.columns().size()) invalidCell("too many columns");
      if (token == null || !token.isScalarValue()) invalidCell("expected a scalar cell");
      String text = token == JsonToken.VALUE_NULL ? null : parser.getText();
      if (text != null) {
        characters += text.length();
        if (text.length() > 32767 || characters > 131072)
          invalidCell("cell or row length limit exceeded");
      }
      cells.add(new RawCell(token, text));
    }
    if (cells.size() != metadata.columns().size())
      invalidCell("column count does not match metadata");
    return new RawRow(rowNumber, List.copyOf(cells));
  }

  public void finish() throws IOException {
    expect(parser.nextToken(), JsonToken.END_OBJECT);
    if (parser.nextToken() != null)
      throw new InvalidReportException("Trailing JSON is not allowed.");
  }

  private void invalidCell(String reason) {
    throw new InvalidReportException("Invalid row " + rowNumber + ": " + reason + ".");
  }

  private void expectField(String name) throws IOException {
    expect(parser.nextToken(), JsonToken.FIELD_NAME);
    if (!name.equals(parser.currentName()))
      throw new InvalidReportException("Expected field: " + name + ".");
  }

  private static void expect(JsonToken actual, JsonToken expected) {
    if (actual != expected)
      throw new InvalidReportException("Invalid report structure; expected " + expected + ".");
  }

  @Override
  public void close() throws IOException {
    parser.close();
  }
}
