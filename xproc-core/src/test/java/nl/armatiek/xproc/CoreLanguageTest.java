package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CoreLanguageTest extends TestSupport {

  @Test
  void identityInline() {
    assertEquals("<doc/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:identity><p:with-input><doc/></p:with-input></p:identity></p:declare-step>"));
  }

  @Test
  void pipelineInput() {
    XProcPipeline p = PROCESSOR.compile("<p:declare-step " + P + "><p:input port='source'/>"
        + "<p:output port='result'/><p:identity/></p:declare-step>", base(), Map.of());
    XProcResult r = p.run(Map.of("source", List.of(xml("<a>x</a>"))), Map.of());
    assertEquals("<a>x</a>", serialize(r.getDocuments("result").get(0)));
  }

  @Test
  void variablesAndTvt() {
    assertEquals("<r v=\"3\">6</r>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:variable name='x' select='3'/>"
        + "<p:identity><p:with-input><r v='{$x}'>{$x * 2}</r></p:with-input></p:identity>"
        + "</p:declare-step>"));
  }

  @Test
  void optionDefaultsAndShadowing() {
    assertEquals("<r>2 7</r>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:option name='a' select='1'/>"
        + "<p:variable name='a' select='$a + 1'/>"
        + "<p:variable name='b' select='7'/>"
        + "<p:identity><p:with-input><r>{$a} {$b}</r></p:with-input></p:identity>"
        + "</p:declare-step>"));
  }

  @Test
  void forEach() {
    List<String> r = results("<p:declare-step " + P + "><p:output port='result' sequence='true'/>"
        + "<p:for-each><p:with-input select='//i'><doc><i>1</i><i>2</i></doc></p:with-input>"
        + "<p:identity><p:with-input><n p='{p:iteration-position()}' "
        + "s='{p:iteration-size()}'>{string(.)}</n></p:with-input></p:identity>"
        + "</p:for-each></p:declare-step>");
    assertEquals(List.of("<n p=\"1\" s=\"2\">1</n>", "<n p=\"2\" s=\"2\">2</n>"), r);
  }

  @Test
  void chooseWhenOtherwise() {
    assertEquals("<yes/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:identity><p:with-input><doc a='1'/></p:with-input></p:identity>"
        + "<p:choose><p:when test='/doc/@a = 2'><p:identity><p:with-input><no/></p:with-input>"
        + "</p:identity></p:when><p:when test='/doc/@a = 1'><p:identity><p:with-input><yes/>"
        + "</p:with-input></p:identity></p:when><p:otherwise><p:identity><p:with-input><other/>"
        + "</p:with-input></p:identity></p:otherwise></p:choose></p:declare-step>"));
  }

  @Test
  void chooseWithoutMatchCopiesInput() {
    assertEquals("<doc/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:identity><p:with-input><doc/></p:with-input></p:identity>"
        + "<p:choose><p:when test='false()'><p:identity><p:with-input><no/></p:with-input>"
        + "</p:identity></p:when></p:choose></p:declare-step>"));
  }

  @Test
  void ifFalseCopiesInput() {
    assertEquals("<doc/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:identity><p:with-input><doc/></p:with-input></p:identity>"
        + "<p:if test='false()'><p:identity><p:with-input><no/></p:with-input>"
        + "</p:identity></p:if></p:declare-step>"));
  }

  @Test
  void tryCatchErrorPort() {
    String r = result("<p:declare-step " + P + " xmlns:c='http://www.w3.org/ns/xproc-step'>"
        + "<p:output port='result'/>"
        + "<p:try><p:error code='my-error'><p:with-input><message>boom</message></p:with-input>"
        + "</p:error><p:catch name='c'><p:identity><p:with-input pipe='error@c'/></p:identity>"
        + "</p:catch></p:try></p:declare-step>");
    assertTrue(r.contains("code=\"my-error\""), r);
    assertTrue(r.contains("<message>boom</message>"), r);
  }

  @Test
  void viewport() {
    assertEquals("<doc><b>x</b><b>y</b></doc>", result("<p:declare-step " + P + ">"
        + "<p:output port='result'/>"
        + "<p:identity><p:with-input><doc><a>x</a><a>y</a></doc></p:with-input></p:identity>"
        + "<p:viewport match='a'><p:identity><p:with-input><b>{string(.)}</b></p:with-input>"
        + "</p:identity></p:viewport></p:declare-step>"));
  }

  @Test
  void declaredStep() {
    assertEquals("<wrap><doc xmlns:ex=\"http://example.com/ex\"/></wrap>", result("<p:declare-step " + P
        + " xmlns:ex='http://example.com/ex'><p:output port='result'/>"
        + "<p:declare-step type='ex:wrapit'><p:input port='source'/><p:output port='result'/>"
        + "<p:option name='w' select='\"wrap\"'/>"
        + "<p:wrap-sequence wrapper='{$w}'/></p:declare-step>"
        + "<ex:wrapit><p:with-input><doc/></p:with-input></ex:wrapit></p:declare-step>"));
  }

  @Test
  void staticOptionAndUseWhen() {
    assertEquals("<b/>", result("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:option name='mode' static='true' select='\"b\"'/>"
        + "<p:identity use-when='$mode = \"a\"'><p:with-input><a/></p:with-input></p:identity>"
        + "<p:identity use-when='$mode = \"b\"'><p:with-input><b/></p:with-input></p:identity>"
        + "</p:declare-step>"));
  }

  @Test
  void documentProperties() {
    assertEquals("<r>application/xml http://example.com/x.xml</r>", result("<p:declare-step "
        + P + "><p:output port='result'/>"
        + "<p:identity><p:with-input><p:inline document-properties=\"map{'base-uri':"
        + "'http://example.com/x.xml'}\"><doc/></p:inline></p:with-input></p:identity>"
        + "<p:identity><p:with-input><r>{p:document-property(., 'content-type')} "
        + "{base-uri(.)}</r></p:with-input></p:identity></p:declare-step>"));
  }

  @Test
  void jsonInline() {
    XProcResult r = run("<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:identity><p:with-input><p:inline content-type='application/json'>"
        + "{{\"a\": 1}}</p:inline></p:with-input></p:identity></p:declare-step>");
    XProcDocument d = r.getPrimaryDocuments().get(0);
    assertEquals("application/json", d.getContentType().toString());
    assertEquals("{\"a\":1}", serialize(d).replace(" ", ""));
  }

  @Test
  void staticErrors() {
    assertError("XS0044", "<p:declare-step " + P + "><p:unknown-step/></p:declare-step>");
    assertError("XS0062", "<p:declare-step xmlns:p='http://www.w3.org/ns/xproc'>"
        + "<p:identity><p:with-input><a/></p:with-input></p:identity></p:declare-step>");
    assertError("XS0002", "<p:declare-step " + P + "><p:identity name='a'><p:with-input><a/>"
        + "</p:with-input></p:identity><p:identity name='a'/></p:declare-step>");
    assertError("XS0001", "<p:declare-step " + P + "><p:identity name='a'><p:with-input "
        + "pipe='result@b'/></p:identity><p:identity name='b'><p:with-input pipe='result@a'/>"
        + "</p:identity></p:declare-step>");
    assertError("XS0032", "<p:declare-step " + P + "><p:identity/></p:declare-step>");
    assertError("XS0031", "<p:declare-step " + P + "><p:identity foo='bar'><p:with-input><a/>"
        + "</p:with-input></p:identity></p:declare-step>");
    assertError("XS0015", "<p:declare-step " + P + "><p:group/></p:declare-step>");
    assertError("XS0107", "<p:declare-step " + P + "><p:variable name='x' select='$y'/>"
        + "<p:sink><p:with-input><a/></p:with-input></p:sink></p:declare-step>");
  }

  @Test
  void dynamicErrors() {
    assertError("XD0006", "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:xslt><p:with-input port='stylesheet'><a/><b/></p:with-input>"
        + "<p:with-input><doc/></p:with-input></p:xslt></p:declare-step>");
    assertError("XD0001", "<p:declare-step " + P + "><p:variable name='x' select='/a'/>"
        + "<p:sink><p:with-input><a/></p:with-input></p:sink></p:declare-step>");
  }

  @Test
  void forwardReferenceOrdering() {
    assertEquals("<b/>", result("<p:declare-step " + P + "><p:output port='result' pipe='result@a'/>"
        + "<p:identity name='a'><p:with-input pipe='result@b'/></p:identity>"
        + "<p:identity name='b'><p:with-input><b/></p:with-input></p:identity>"
        + "</p:declare-step>"));
  }
}
