package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class HttpRequestTest extends TestSupport {

  private static HttpServer server;
  private static String base;

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/xml", ex -> send(ex, 200, "application/xml", "<doc>hello</doc>"));
    server.createContext("/json", ex -> send(ex, 200, "application/json", "{\"a\": [1, 2]}"));
    server.createContext("/missing", ex -> send(ex, 404, "text/plain", "not found"));
    server.createContext("/echo", ex -> {
      String ct = ex.getRequestHeaders().getFirst("Content-Type");
      byte[] body = ex.getRequestBody().readAllBytes();
      send(ex, 200, ct == null ? "text/plain" : ct, new String(body, StandardCharsets.UTF_8));
    });
    server.createContext("/multipart", ex -> send(ex, 200,
        "multipart/mixed; boundary=XYZ",
        "--XYZ\r\nContent-Type: application/xml\r\n\r\n<one/>\r\n"
            + "--XYZ\r\nContent-Type: text/plain\r\nContent-ID: two\r\n\r\ntwo\r\n--XYZ--\r\n"));
    server.createContext("/secret", ex -> {
      String auth = ex.getRequestHeaders().getFirst("Authorization");
      String expected = "Basic " + Base64.getEncoder().encodeToString(
          "user:pass".getBytes(StandardCharsets.UTF_8));
      if (expected.equals(auth)) {
        send(ex, 200, "application/xml", "<ok/>");
      } else {
        ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
        send(ex, 401, "text/plain", "unauthorized");
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  private static void send(HttpExchange ex, int status, String ct, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", ct);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  void getXml() {
    assertEquals("<doc>hello</doc>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:http-request href='" + base + "/xml'><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>"));
  }

  @Test
  void getJsonAndReport() {
    XProcResult r = run("<p:declare-step " + P + "><p:output port='result' sequence='true' "
        + "primary='true'/><p:output port='report' pipe='report@req'/>"
        + "<p:http-request name='req' href='" + base + "/json'><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>");
    XProcDocument json = r.getDocuments("result").get(0);
    assertEquals(MediaType.Kind.JSON, json.getKind());
    String report = serialize(r.getDocuments("report").get(0));
    assertTrue(report.contains("\"status-code\":200"), report);
  }

  @Test
  void assertionFails() {
    assertError("XC0126", "<p:declare-step " + P + "><p:output port='result' sequence='true'/>"
        + "<p:http-request href='" + base + "/missing'><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>");
  }

  @Test
  void assertionDisabled() {
    assertEquals("not found", result("<p:declare-step " + P + ">"
        + "<p:output port='result' sequence='true'/>"
        + "<p:http-request assert='true()' href='" + base + "/missing'><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>"));
  }

  @Test
  void postEcho() {
    assertEquals("<posted/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:http-request method='post' href='" + base + "/echo'>"
        + "<p:with-input><posted/></p:with-input></p:http-request></p:declare-step>"));
  }

  @Test
  void multipartResponse() {
    List<String> r = results("<p:declare-step " + P + "><p:output port='result' "
        + "sequence='true'/><p:http-request href='" + base + "/multipart'><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>");
    assertEquals(List.of("<one/>", "two"), r);
  }

  @Test
  void basicAuthChallenge() {
    assertEquals("<ok/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:http-request href='" + base + "/secret' auth=\"map{'username': 'user', "
        + "'password': 'pass', 'auth-method': 'Basic'}\"><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>"));
  }

  @Test
  void unsupportedMethod() {
    assertError("XC0122", "<p:declare-step " + P + "><p:output port='result' sequence='true'/>"
        + "<p:http-request method='frobnicate' href='" + base + "/xml'><p:with-input><p:empty/></p:with-input></p:http-request></p:declare-step>");
  }
}
