package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.Map;

import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.Test;

/** Static errors report the line of the pipeline document where they occur. */
class ErrorLocationTest extends TestSupport {

  @Test
  void staticErrorHasLine() {
    XProcException e = assertThrows(XProcException.class, () -> PROCESSOR.compile(
        "<p:declare-step " + P + ">\n  <p:no-such-step/>\n</p:declare-step>", base(),
        Map.of()));
    assertEquals("XS0044", e.getCode().getLocalName());
    assertEquals(2, e.getLine());
  }

  @Test
  void parseErrorHasLine() {
    StreamSource src = new StreamSource(new StringReader(
        "<p:declare-step " + P + ">\n  <p:identity\n</p:declare-step>"));
    src.setSystemId(base().toString());
    XProcException e = assertThrows(XProcException.class, () -> PROCESSOR.compile(src,
        Map.of()));
    assertEquals("XS0100", e.getCode().getLocalName());
    assertEquals(3, e.getLine());
  }
}
