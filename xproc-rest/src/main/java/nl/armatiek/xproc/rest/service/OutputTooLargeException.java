package nl.armatiek.xproc.rest.service;

/** The primary output port of a transform produced more than one document (status 422). */
public class OutputTooLargeException extends RuntimeException {

  public OutputTooLargeException(String message) {
    super(message);
  }
}
