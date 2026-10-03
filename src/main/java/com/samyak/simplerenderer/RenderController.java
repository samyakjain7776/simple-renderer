package com.samyak.simplerenderer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RestController
final class RenderController {
    private final RenderService service;
    RenderController(RenderService service) { this.service = service; }

    @PostMapping(path = "/reports", consumes = "application/json")
    void render(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try (var artifact = service.render(request.getInputStream())) {
            var metadata = artifact.metadata();
            response.setContentType(metadata.contentType());
            response.setContentLengthLong(Files.size(artifact.path()));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(metadata.name() + "." + metadata.fileType(), StandardCharsets.UTF_8).build().toString());
            Files.copy(artifact.path(), response.getOutputStream());
        }
    }
}
