package nl.armatiek.xproc.rest.controller;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.rest.model.Problem;
import nl.armatiek.xproc.rest.service.BadRequestException;
import nl.armatiek.xproc.rest.service.OutputTooLargeException;
import nl.armatiek.xproc.rest.service.PipelineNotFoundException;

/** Reports errors as problem details ({@code application/problem+json}). */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** Static errors make the request invalid, other errors occur while running a pipeline. */
  @ExceptionHandler(XProcException.class)
  public ResponseEntity<Problem> xproc(XProcException e, HttpServletRequest req) {
    HttpStatus status = e.isStatic() ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_CONTENT;
    Problem p = problem(status, e.isStatic() ? "Static error" : "Dynamic error", e.getMessage(),
        req)
        .type(URI.create("urn:xproc:error:" + e.getCode().getLocalName()))
        .code(e.getCode().getEQName())
        .causeCode(e.getCauseCode() == null ? null : e.getCauseCode().getEQName())
        .location(e.getLocation())
        .line(e.getLine() > 0 ? e.getLine() : null)
        .column(e.getColumn() > 0 ? e.getColumn() : null)
        .step(e.getStepName())
        .stepType(e.getStepType() == null ? null : e.getStepType().getEQName());
    return respond(p);
  }

  @ExceptionHandler(PipelineNotFoundException.class)
  public ResponseEntity<Problem> notFound(PipelineNotFoundException e, HttpServletRequest req) {
    return respond(problem(HttpStatus.NOT_FOUND, "Pipeline not found", e.getMessage(), req));
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Problem> badRequest(BadRequestException e, HttpServletRequest req) {
    return respond(problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), req));
  }

  @ExceptionHandler(OutputTooLargeException.class)
  public ResponseEntity<Problem> tooLarge(OutputTooLargeException e, HttpServletRequest req) {
    return respond(problem(HttpStatus.UNPROCESSABLE_CONTENT, "More than one output document",
        e.getMessage(), req));
  }

  @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
  public ResponseEntity<Problem> constraint(jakarta.validation.ConstraintViolationException e,
      HttpServletRequest req) {
    return respond(problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), req));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Problem> unreadable(HttpMessageNotReadableException e,
      HttpServletRequest req) {
    return respond(problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), req));
  }

  /** Spring MVC errors (validation, unsupported media types, ...) and unexpected errors. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Problem> other(Exception e, HttpServletRequest req) {
    if (e instanceof ErrorResponse er) {
      HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
      return respond(problem(status == null ? HttpStatus.BAD_REQUEST : status,
          er.getBody().getTitle(), er.getBody().getDetail(), req));
    }
    LOG.error("Unexpected error processing {}", req.getRequestURI(), e);
    return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", e.toString(),
        req));
  }

  private static Problem problem(HttpStatus status, String title, String detail,
      HttpServletRequest req) {
    return new Problem()
        .status(status.value())
        .title(title)
        .detail(detail)
        .instance(URI.create(req.getRequestURI()));
  }

  private static ResponseEntity<Problem> respond(Problem p) {
    return ResponseEntity.status(p.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(p);
  }
}
