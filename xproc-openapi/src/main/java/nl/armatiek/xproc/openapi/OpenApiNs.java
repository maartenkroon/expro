package nl.armatiek.xproc.openapi;

/**
 * The namespaces of the pipeline options through which a request is passed to a pipeline, and
 * of the document properties through which a pipeline shapes the response.
 */
public final class OpenApiNs {

  private static final String BASE = "http://www.armatiek.nl/ns/xproc/openapi/";

  /** OpenAPI path parameters ({@code in: path}). */
  public static final String PATH = BASE + "parameter/path";

  /** OpenAPI query parameters ({@code in: query}). */
  public static final String QUERY = BASE + "parameter/query";

  /** OpenAPI header parameters ({@code in: header}). */
  public static final String HEADER = BASE + "parameter/header";

  /** OpenAPI cookie parameters ({@code in: cookie}). */
  public static final String COOKIE = BASE + "parameter/cookie";

  /** The non-file parts of a multipart request. */
  public static final String PART = BASE + "multipart/part";

  /** General information about the request (method, URI, all headers, ...). */
  public static final String REQUEST = BASE + "request";

  /** Document properties of the primary output document that set the response status and headers. */
  public static final String RESPONSE = BASE + "response";

  private OpenApiNs() {
  }

  /** The option namespace for the parameters of an OpenAPI location ({@code in}). */
  public static String forLocation(String in) {
    return switch (in) {
      case "path" -> PATH;
      case "query" -> QUERY;
      case "header" -> HEADER;
      case "cookie" -> COOKIE;
      default -> throw new IllegalArgumentException("Unknown parameter location: " + in);
    };
  }
}
