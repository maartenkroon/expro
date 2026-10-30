package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Helpers for running small pipelines in tests. */
public abstract class TestSupport {

  protected static final XProcProcessor PROCESSOR = new XProcProcessor(
      new XProcConfiguration().setMessageListener(MessageListener.SILENT));

  protected static final String P = "xmlns:p='http://www.w3.org/ns/xproc' version='3.1'";

  protected static URI base() {
    return java.nio.file.Path.of("target/test-base/").toAbsolutePath().toUri();
  }

  protected XProcResult run(String pipeline) {
    return run(pipeline, Map.of());
  }

  protected XProcResult run(String pipeline, Map<String, List<XProcDocument>> inputs) {
    XProcPipeline p = PROCESSOR.compile(pipeline, base(), Map.of());
    return p.run(inputs, Map.of());
  }

  /** Runs a pipeline and returns the serializations of the primary output documents. */
  protected List<String> results(String pipeline) {
    XProcResult r = run(pipeline);
    List<String> list = new ArrayList<>();
    for (XProcDocument d : r.getPrimaryDocuments()) {
      list.add(serialize(d));
    }
    return list;
  }

  protected String result(String pipeline) {
    List<String> r = results(pipeline);
    assertEquals(1, r.size(), "expected exactly one result document, got " + r);
    return r.get(0);
  }

  protected static String serialize(XProcDocument d) {
    return PROCESSOR.serializeToString(d, Map.of(
        new net.sf.saxon.s9api.QName("omit-xml-declaration"),
        new net.sf.saxon.s9api.XdmAtomicValue(true)));
  }

  /** Asserts that compiling or running a pipeline fails with the given error code. */
  protected void assertError(String code, String pipeline) {
    try {
      run(pipeline);
      fail("Expected error " + code);
    } catch (XProcException e) {
      assertEquals(code, e.getCode().getLocalName(), "Wrong error: " + e);
    }
  }

  protected static XProcDocument xml(String s) {
    return PROCESSOR.parseDocument(s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        MediaType.XML, URI.create("http://example.com/doc.xml"));
  }
}
