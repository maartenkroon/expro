package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** p:import-functions with XQuery library modules, XQuery main modules and XSLT packages. */
class FunctionImportTest extends TestSupport {

  private static final String F = "xmlns:f='http://example.com/f'";

  @TempDir
  static Path dir;

  @BeforeAll
  static void writeLibraries() throws IOException {
    Files.writeString(dir.resolve("library.xq"), """
        xquery version "3.1";
        (: a library module :)
        module namespace f = "http://example.com/f";
        declare variable $f:greeting := "Hello";
        declare function f:greet($name as xs:string) as xs:string {
          $f:greeting || ", " || $name
        };
        declare %private function f:secret() as xs:string { "secret" };
        """);
    Files.writeString(dir.resolve("main.xq"), """
        xquery version "3.1";
        declare namespace f = "http://example.com/f";
        declare namespace g = "http://example.com/g";
        declare function f:twice($n as xs:integer) as xs:integer { 2 * $n };
        declare function g:other() as xs:string { "other" };
        42
        """);
    Files.writeString(dir.resolve("functions.xsl"), """
        <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
            xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:f="http://example.com/f">
          <xsl:function name="f:twice" as="xs:integer" visibility="public">
            <xsl:param name="n" as="xs:integer"/>
            <xsl:sequence select="2 * $n"/>
          </xsl:function>
        </xsl:stylesheet>
        """);
  }

  private String run(String imports, String expression) {
    XProcPipeline p = PROCESSOR.compile("<p:declare-step " + P + " " + F
        + " exclude-inline-prefixes='f'>" + imports + "<p:output port='result'/><p:identity><p:with-input><r>{" + expression + "}</r>"
        + "</p:with-input></p:identity></p:declare-step>", dir.resolve("p.xpl").toUri(),
        Map.of());
    return serialize(p.run(Map.of(), Map.of()).getDocuments("result").get(0));
  }

  private XProcException error(String imports, String expression) {
    return assertThrows(XProcException.class, () -> run(imports, expression));
  }

  @Test
  void libraryModule() {
    assertEquals("<r>Hello, XProc</r>", run("<p:import-functions href='library.xq' "
        + "content-type='application/xquery'/>", "f:greet('XProc')"));
  }

  @Test
  void mainModuleWithContentTypeFromExtension() {
    assertEquals("<r>42</r>", run("<p:import-functions href='main.xq'/>", "f:twice(21)"));
  }

  @Test
  void privateFunctionsAreNotImported() {
    assertEquals("XS0107", error("<p:import-functions href='library.xq'/>", "f:secret()")
        .getCode().getLocalName());
  }

  @Test
  void namespaceFilter() {
    String imp = "<p:import-functions href='main.xq' namespace='http://example.com/g'/>";
    assertEquals("<r>other</r>", run(imp, "Q{http://example.com/g}other()"));
    assertEquals("XS0107", error(imp, "f:twice(1)").getCode().getLocalName());
  }

  @Test
  void duplicateFunctions() {
    assertEquals("XS0105", error("<p:import-functions href='main.xq'/>"
        + "<p:import-functions href='functions.xsl'/>", "f:twice(1)").getCode().getLocalName());
  }

  @Test
  void invalidLibrary() throws IOException {
    Files.writeString(dir.resolve("invalid.xq"), "xquery version '3.1'; 13 + ");
    assertEquals("XS0106", error("<p:import-functions href='invalid.xq'/>", "1")
        .getCode().getLocalName());
  }

  @Test
  void xqueryIsImportable() {
    assertTrue(run("", "p:function-library-importable('application/xquery')").contains("true"));
  }
}
