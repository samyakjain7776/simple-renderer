package com.samyak.simplerenderer.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RendererPropertiesTest {
  @Test
  void defaultLimitsArePositive() {
    var props = new RendererProperties();
    assertTrue(props.getMaxInputBytes() > 0);
    assertTrue(props.getMaxOutputBytes() > 0);
    assertTrue(props.getMaxRows() > 0);
    assertTrue(props.getMaxConcurrentReports() > 0);
  }
}
