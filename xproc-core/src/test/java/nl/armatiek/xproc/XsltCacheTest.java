package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Tests the reuse of compiled XSLT stylesheets. */
class XsltCacheTest extends TestSupport {

  private static String pipeline(String text) {
    return "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:xslt><p:with-input port='source'><doc/></p:with-input>"
        + "<p:with-input port='stylesheet'><xsl:stylesheet version='3.0' "
        + "xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:template match='/'>"
        + "<r>" + text + "</r></xsl:template></xsl:stylesheet></p:with-input></p:xslt>"
        + "</p:declare-step>";
  }

  @Test
  void stylesheetsAreCompiledOnce() {
    XProcProcessor processor = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT));
    XProcPipeline a = processor.compile(pipeline("a"), base(), Map.of());
    assertEquals("<r>a</r>", processor.serializeToString(a.run(Map.of(), Map.of())
        .getPrimaryDocuments().get(0), Map.of()).replaceFirst("<\\?xml[^>]*>", ""));
    a.run(Map.of(), Map.of());
    // Compiling the same pipeline again yields the same stylesheet
    processor.compile(pipeline("a"), base(), Map.of()).run(Map.of(), Map.of());
    assertEquals(1, processor.getCachedStylesheetCount());

    processor.compile(pipeline("b"), base(), Map.of()).run(Map.of(), Map.of());
    assertEquals(2, processor.getCachedStylesheetCount());

    processor.clearCaches();
    assertEquals(0, processor.getCachedStylesheetCount());
  }

  @Test
  void cacheCanBeDisabled() {
    XProcProcessor processor = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT).setXsltCacheSize(0));
    processor.compile(pipeline("a"), base(), Map.of()).run(Map.of(), Map.of());
    assertEquals(0, processor.getCachedStylesheetCount());
  }

  @Test
  void leastRecentlyUsedStylesheetsAreDropped() {
    XProcProcessor processor = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT).setXsltCacheSize(2));
    for (String s : new String[] {"a", "b", "c"}) {
      processor.compile(pipeline(s), base(), Map.of()).run(Map.of(), Map.of());
    }
    assertEquals(2, processor.getCachedStylesheetCount());
  }
}
