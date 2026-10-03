package com.samyak.simplerenderer;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RenderApiTest {
  @LocalServerPort int port;

  @Test
  void downloadsCompletedCsvAndReportsInvalidInput() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var valid =
          send(
              client,
              RenderServiceTest.report("csv", RenderServiceTest.TEXT_COLUMN, "[[\"hello\"]]"));
      assertEquals(200, valid.statusCode());
      assertTrue(valid.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
      assertTrue(
          valid.headers().firstValue("Content-Disposition").orElseThrow().contains("Test.csv"));
      assertEquals("\"Text\"\r\n\"hello\"\r\n", valid.body());
      var invalid = send(client, "{}");
      assertEquals(400, invalid.statusCode());
      assertTrue(
          invalid.headers().firstValue("Content-Type").orElseThrow().contains("problem+json"));
      assertFalse(invalid.body().contains("com.samyak"));
    }
  }

  private HttpResponse<String> send(HttpClient client, String json) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/reports"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
