package com.samyak.simplerenderer.api;

import com.fasterxml.jackson.core.JacksonException;
import com.samyak.simplerenderer.domain.InvalidReportException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RenderErrorHandler {
  @ExceptionHandler(InvalidReportException.class)
  public ProblemDetail invalidReport(InvalidReportException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(JacksonException.class)
  public ProblemDetail malformedJson() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "Invalid JSON or report metadata.");
  }
}
