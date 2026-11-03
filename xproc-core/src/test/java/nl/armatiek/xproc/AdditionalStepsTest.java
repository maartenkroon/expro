package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the file, operating system, p:run and validation steps. */
class AdditionalStepsTest extends TestSupport {

  private static final String C = "xmlns:c='http://www.w3.org/ns/xproc-step'";

  @TempDir
  Path dir;

  private String uri(String relative) {
    return dir.resolve(relative).toUri().toString();
  }

  @Test
  void fileStepsRoundTrip() throws Exception {
    Files.writeString(dir.resolve("a.txt"), "hello");
    String out = result("<p:declare-step " + P + " " + C + "><p:output port='result'/>"
        + "<p:file-mkdir name='mkdir' href='" + uri("sub/deeper") + "'/>"
        + "<p:file-copy name='copy' depends='mkdir' href='" + uri("a.txt") + "' target='"
        + uri("sub/deeper/") + "'/>"
        + "<p:file-move name='move' depends='copy' href='" + uri("sub/deeper/a.txt")
        + "' target='" + uri("sub/b.txt") + "'/>"
        + "<p:directory-list depends='move' max-depth='unbounded' path='" + uri("sub") + "'/>"
        + "</p:declare-step>");
    assertTrue(out.contains("<c:file xml:base=\"b.txt\" name=\"b.txt\"/>"), out);
    assertTrue(out.contains("<c:directory xml:base=\"deeper/\" name=\"deeper\"/>"), out);
    assertEquals("hello", Files.readString(dir.resolve("sub/b.txt")));
    assertTrue(Files.exists(dir.resolve("a.txt")));
  }

  @Test
  void fileInfoAndDelete() throws Exception {
    Files.writeString(dir.resolve("data.xml"), "<doc/>");
    String info = result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:file-info href='" + uri("data.xml") + "'/></p:declare-step>");
    assertTrue(info.contains("content-type=\"application/xml\""), info);
    assertTrue(info.contains("size=\"6\""), info);
    result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:file-delete href='" + uri("data.xml") + "'/></p:declare-step>");
    assertFalse(Files.exists(dir.resolve("data.xml")));
  }

  @Test
  void failOnErrorFalseReturnsCError() {
    String out = result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:file-move fail-on-error='false' href='" + uri("missing") + "' target='"
        + uri("x") + "'/></p:declare-step>");
    assertTrue(out.contains("code=\"{http://www.w3.org/ns/xproc-error}XD0011\""), out);
  }

  @Test
  void fileAccessCanBeForbidden() {
    XProcProcessor safe = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT).setAllowFileAccess(false));
    try {
      safe.compile("<p:declare-step " + P + "><p:output port='result'/>"
          + "<p:file-mkdir href='" + uri("nope") + "'/></p:declare-step>", base(), Map.of())
          .run(Map.of(), Map.of());
      fail("Expected err:XC0141");
    } catch (XProcException e) {
      assertEquals("XC0141", e.getCode().getLocalName());
    }
    assertFalse(Files.exists(dir.resolve("nope")));
  }

  @Test
  void osInfoAndExec() {
    assertTrue(result("<p:declare-step " + P + "><p:output port='result'/><p:os-info/>"
        + "</p:declare-step>").contains("file-separator=\"" + java.io.File.separator + "\""));
    if (!System.getProperty("os.name").startsWith("Windows")) {
      String out = result("<p:declare-step " + P + "><p:output port='result'/>"
          + "<p:os-exec command='cat'><p:with-input><doc>x</doc></p:with-input></p:os-exec>"
          + "</p:declare-step>");
      assertTrue(out.contains("<doc>x</doc>"), out);
    }
  }

  @Test
  void osAccessCanBeForbidden() {
    XProcProcessor safe = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT).setAllowOsAccess(false));
    try {
      safe.compile("<p:declare-step " + P + "><p:output port='result'/>"
          + "<p:os-exec command='echo'><p:with-input><p:empty/></p:with-input></p:os-exec>"
          + "</p:declare-step>", base(), Map.of())
          .run(Map.of(), Map.of());
      fail("Expected err:XC0033");
    } catch (XProcException e) {
      assertEquals("XC0033", e.getCode().getLocalName());
    }
  }

  @Test
  void runPipeline() {
    assertEquals("<r>hi there</r>", result("<p:declare-step " + P + ">"
        + "<p:output port='result'/>"
        + "<p:run>"
        + "<p:with-input><p:inline expand-text='false'><p:declare-step version='3.1'>"
        + "<p:input port='source'/><p:output port='result'/><p:option name='who'/>"
        + "<p:identity><p:with-input><r>{string(.)} {$who}</r></p:with-input></p:identity>"
        + "</p:declare-step></p:inline></p:with-input>"
        + "<p:run-input port='source'><doc>hi</doc></p:run-input>"
        + "<p:run-option name='who' select=\"'there'\"/>"
        + "<p:output port='result'/>"
        + "</p:run></p:declare-step>"));
  }

  @Test
  void runRejectsInvalidPipeline() {
    assertError("XC0200", "<p:declare-step " + P + "><p:output port='result' sequence='true'/>"
        + "<p:run><p:with-input><not-a-pipeline/></p:with-input>"
        + "<p:output port='result' sequence='true'/></p:run></p:declare-step>");
  }

  private static final String RNG = "<p:with-input port='schema'><element name='doc' "
      + "xmlns='http://relaxng.org/ns/structure/1.0'><text/></element></p:with-input>";

  @Test
  void relaxNg() {
    assertEquals("<doc>x</doc>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:validate-with-relax-ng><p:with-input port='source'><doc>x</doc></p:with-input>"
        + RNG + "</p:validate-with-relax-ng></p:declare-step>"));
    assertError("XC0155", "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:validate-with-relax-ng><p:with-input port='source'><other/></p:with-input>"
        + RNG + "</p:validate-with-relax-ng></p:declare-step>");
  }

  @Test
  void xvrlReportWhenNotAsserting() {
    String report = result("<p:declare-step " + P + "><p:output port='result' pipe='report@v'/>"
        + "<p:validate-with-relax-ng name='v' assert-valid='false'>"
        + "<p:with-input port='source'><other/></p:with-input>" + RNG
        + "</p:validate-with-relax-ng></p:declare-step>");
    assertTrue(report.contains("xmlns:xvrl=\"http://www.xproc.org/ns/xvrl\""), report);
    assertTrue(report.contains("<xvrl:detection severity=\"error\""), report);
    assertTrue(report.contains("valid=\"false\""), report);
  }

  @Test
  void xmlSchema() {
    String xsd = "<p:with-input port='schema'><xs:schema "
        + "xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:element name='doc'><xs:complexType>"
        + "<xs:attribute name='a' default='1'/></xs:complexType></xs:element></xs:schema>"
        + "</p:with-input>";
    assertEquals("<doc a=\"1\"/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:validate-with-xml-schema><p:with-input port='source'><doc/></p:with-input>" + xsd
        + "</p:validate-with-xml-schema></p:declare-step>"));
    assertError("XC0156", "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:validate-with-xml-schema><p:with-input port='source'><doc b='2'/></p:with-input>"
        + xsd + "</p:validate-with-xml-schema></p:declare-step>");
  }

  @Test
  void schematron() {
    String sch = "<p:with-input port='schema'><s:schema "
        + "xmlns:s='http://purl.oclc.org/dsdl/schematron'><s:pattern><s:rule context='doc'>"
        + "<s:assert test='@id'>doc needs an id</s:assert></s:rule></s:pattern></s:schema>"
        + "</p:with-input>";
    String svrl = result("<p:declare-step " + P + "><p:output port='result' pipe='report@v'/>"
        + "<p:validate-with-schematron name='v' assert-valid='false'>"
        + "<p:with-input port='source'><doc/></p:with-input>" + sch
        + "</p:validate-with-schematron></p:declare-step>");
    assertTrue(svrl.contains("failed-assert"), svrl);
    assertTrue(svrl.contains("doc needs an id"), svrl);
    assertError("XC0054", "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:validate-with-schematron><p:with-input port='source'><doc/></p:with-input>" + sch
        + "</p:validate-with-schematron></p:declare-step>");
  }

  @Test
  void jsonSchema() {
    String pipeline = "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:validate-with-json-schema>"
        + "<p:with-input port='source'><p:inline content-type='application/json' "
        + "expand-text='false'>"
        + "%s</p:inline></p:with-input>"
        + "<p:with-input port='schema'><p:inline content-type='application/json' "
        + "expand-text='false'>"
        + "{\"type\": \"object\", \"required\": [\"name\"]}</p:inline></p:with-input>"
        + "</p:validate-with-json-schema></p:declare-step>";
    run(String.format(pipeline, "{\"name\": \"x\"}"));
    assertError("XC0165", String.format(pipeline, "{\"other\": 1}"));
  }
}
