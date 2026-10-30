package nl.armatiek.xproc.rest.service;

/** There is no stored pipeline with the requested identifier (status 404). */
public class PipelineNotFoundException extends RuntimeException {

  public PipelineNotFoundException(String id) {
    super("There is no pipeline with identifier '" + id + "'");
  }
}
