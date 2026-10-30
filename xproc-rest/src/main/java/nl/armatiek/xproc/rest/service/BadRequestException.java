package nl.armatiek.xproc.rest.service;

/** The request is invalid (status 400). */
public class BadRequestException extends RuntimeException {

  public BadRequestException(String message) {
    super(message);
  }

  public BadRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
