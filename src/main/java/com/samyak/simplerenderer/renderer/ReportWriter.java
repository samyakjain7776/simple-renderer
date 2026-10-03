package com.samyak.simplerenderer.renderer;

import java.io.IOException;
import java.util.List;

public interface ReportWriter extends AutoCloseable {
  void append(List<Object> row) throws IOException;

  void finish() throws IOException;

  @Override
  void close() throws IOException;
}
