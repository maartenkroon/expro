package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests for p:send-mail (against an in-process SMTP server) and p:markdown-to-html. */
class MailAndTextStepsTest extends TestSupport {

  @Test
  void markdownToHtml() {
    XProcDocument doc = run("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:markdown-to-html><p:with-input><p:inline content-type='text/plain'>"
        + "# Title\n\nSome *emphasis* and ~~strike~~.\n\n| a | b |\n|---|---|\n| 1 | 2 |\n"
        + "</p:inline></p:with-input></p:markdown-to-html></p:declare-step>")
        .getPrimaryDocuments().get(0);
    assertEquals("text/html", doc.getContentType().toString());
    net.sf.saxon.s9api.XdmNode root = doc.getNode().children().iterator().next();
    assertEquals("Q{http://www.w3.org/1999/xhtml}html", root.getNodeName().getEQName());
    String html = serialize(doc);
    assertTrue(html.contains("<h1>Title</h1>"), html);
    assertTrue(html.contains("<em>emphasis</em>"), html);
    assertTrue(html.contains("<del>strike</del>"), html);
    assertTrue(html.contains("<td>1</td>"), html);
  }

  private static final String MESSAGE = "<emx:Message xmlns:emx='URN:ietf:params:email-xml:' "
      + "xmlns:rfc822='URN:ietf:params:rfc822:'>"
      + "<rfc822:from><emx:Address><emx:adrs>mailto:user@example.com</emx:adrs>"
      + "<emx:name>Example User</emx:name></emx:Address></rfc822:from>"
      + "<rfc822:to><emx:Address><emx:adrs>mailto:other@example.com</emx:adrs>"
      + "</emx:Address></rfc822:to>"
      + "<rfc822:subject>Test message</rfc822:subject>%s</emx:Message>";

  @Test
  void sendTextMail() throws Exception {
    try (SmtpServer smtp = new SmtpServer()) {
      String r = result(pipeline(smtp.port(), String.format(MESSAGE,
          "<emx:content type='text/plain'>Do that thing.</emx:content>"), ""));
      assertEquals("<c:result xmlns:c=\"http://www.w3.org/ns/xproc-step\">true</c:result>", r);
      String data = smtp.data();
      assertTrue(smtp.commands().contains("MAIL FROM:<user@example.com>"), smtp.commands()
          .toString());
      assertTrue(smtp.commands().contains("RCPT TO:<other@example.com>"));
      assertTrue(data.contains("Subject: Test message"), data);
      assertTrue(data.contains("Content-Type: text/plain; charset=UTF-8"), data);
      assertTrue(data.contains("Do that thing."), data);
    }
  }

  @Test
  void sendHtmlMailWithAttachment() throws Exception {
    try (SmtpServer smtp = new SmtpServer()) {
      result(pipeline(smtp.port(), String.format(MESSAGE, "<emx:content>"
          + "<html xmlns='http://www.w3.org/1999/xhtml'><body><p>Hi</p></body></html>"
          + "</emx:content>"), "<p:inline><attached/></p:inline>"));
      String data = smtp.data();
      assertTrue(data.contains("multipart/mixed"), data);
      assertTrue(data.contains("Content-Type: text/html; charset=UTF-8"), data);
      assertTrue(data.contains("Content-Type: application/xml; charset=UTF-8"), data);
      assertTrue(data.contains("<attached/>"), data);
    }
  }

  @Test
  void invalidMessage() {
    assertError("XC0161", pipeline(25, "<not-a-message/>", ""));
  }

  @Test
  void mailNeedsNetworkAccess() {
    XProcProcessor safe = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT).setAllowNetworkAccess(false));
    try {
      safe.compile(pipeline(25, String.format(MESSAGE, ""), ""), base(), java.util.Map.of())
          .run(java.util.Map.of(), java.util.Map.of());
      org.junit.jupiter.api.Assertions.fail("Expected err:XD0021");
    } catch (XProcException e) {
      assertEquals("XD0021", e.getCode().getLocalName());
    }
  }

  @Test
  void unreachableServer() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    assertError("XC0162", pipeline(port, String.format(MESSAGE, ""), ""));
  }

  private static String pipeline(int port, String message, String attachments) {
    return "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:send-mail parameters=\"map{'host': 'localhost', 'port': " + port + "}\">"
        + "<p:with-input><p:inline>" + message + "</p:inline>" + attachments + "</p:with-input>"
        + "</p:send-mail></p:declare-step>";
  }

  /** A minimal SMTP server that accepts one message. */
  private static final class SmtpServer implements AutoCloseable {
    private final ServerSocket server = new ServerSocket(0);
    private final List<String> commands = new ArrayList<>();
    private final StringBuilder data = new StringBuilder();
    private final Thread thread;

    SmtpServer() throws IOException {
      thread = Thread.ofVirtual().start(this::serve);
    }

    int port() {
      return server.getLocalPort();
    }

    List<String> commands() throws InterruptedException {
      thread.join(5000);
      return commands;
    }

    String data() throws InterruptedException {
      thread.join(5000);
      return data.toString();
    }

    private void serve() {
      try (Socket s = server.accept();
          BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
              StandardCharsets.UTF_8));
          PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
        out.print("220 localhost ESMTP\r\n");
        out.flush();
        String line;
        while ((line = in.readLine()) != null) {
          commands.add(line);
          String cmd = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line;
          switch (cmd) {
            case "EHLO", "HELO" -> out.print("250 localhost\r\n");
            case "DATA" -> {
              out.print("354 End data with <CR><LF>.<CR><LF>\r\n");
              out.flush();
              String d;
              while ((d = in.readLine()) != null && !d.equals(".")) {
                data.append(d).append('\n');
              }
              out.print("250 OK\r\n");
            }
            case "QUIT" -> {
              out.print("221 Bye\r\n");
              out.flush();
              return;
            }
            default -> out.print("250 OK\r\n");
          }
          out.flush();
        }
      } catch (IOException e) {
        // the test fails on missing data
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
