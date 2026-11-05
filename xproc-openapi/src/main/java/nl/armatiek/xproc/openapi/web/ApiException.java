package nl.armatiek.xproc.openapi.web;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** A request that cannot be handled, reported to the client as a problem detail. */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;
  private final transient Map<String, String> headers;

  public ApiException(HttpStatus status, String detail) {
    this(status, detail, Map.of(), null);
  }

  public ApiException(HttpStatus status, String detail, Throwable cause) {
    this(status, detail, Map.of(), cause);
  }

  public ApiException(HttpStatus status, String detail, Map<String, String> headers,
      Throwable cause) {
    super(detail, cause);
    this.status = status;
    this.headers = headers;
  }

  public HttpStatus getStatus() {
    return status;
  }

  /** Response headers to add (for example Allow). */
  public Map<String, String> getHeaders() {
    return headers;
  }
}
