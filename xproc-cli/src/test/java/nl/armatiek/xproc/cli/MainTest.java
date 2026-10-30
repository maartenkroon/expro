package nl.armatiek.xproc.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

  @TempDir
  Path dir;

  private record Run(int rc, String out, String err) {
  }

  private Run run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc = new Main(new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    return new Run(rc, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void runsPipelineWithInputOptionAndOutput() throws Exception {
    Path xpl = dir.resolve("p.xpl");
    Files.writeString(xpl, """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
          <p:input port="source"/>
          <p:output port="result"/>
          <p:option name="name" select="'x'"/>
          <p:add-attribute match="/*" attribute-name="name" attribute-value="{$name}"/>
        </p:declare-step>""");
    Path in = dir.resolve("in.xml");
    Files.writeString(in, "<doc/>");
    Path outFile = dir.resolve("out/result.xml");
    Run r = run("-i", "source=" + in, "-o", "result=" + outFile, xpl.toString(), "name=hello");
    assertEquals(Main.OK, r.rc(), r.err());
    assertTrue(Files.readString(outFile).contains("<doc name=\"hello\"/>"));
  }

  @Test
  void primaryOutputGoesToStdout() throws Exception {
    Path xpl = dir.resolve("p.xpl");
    Files.writeString(xpl, """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
          <p:output port="result"/>
          <p:identity><p:with-input><hello/></p:with-input></p:identity>
        </p:declare-step>""");
    Run r = run(xpl.toString());
    assertEquals(Main.OK, r.rc(), r.err());
    assertTrue(r.out().contains("<hello/>"), r.out());
  }

  @Test
  void reportsPipelineErrors() throws Exception {
    Path xpl = dir.resolve("p.xpl");
    Files.writeString(xpl, """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
          <p:unknown/>
        </p:declare-step>""");
    Run r = run(xpl.toString());
    assertEquals(Main.PIPELINE_ERROR, r.rc());
    assertTrue(r.err().contains("err:XS0044"), r.err());
  }

  @Test
  void usageErrors() {
    assertEquals(Main.USAGE_ERROR, run().rc());
    assertEquals(Main.USAGE_ERROR, run("--bogus", "x.xpl").rc());
    assertEquals(Main.OK, run("--help").rc());
  }
}
