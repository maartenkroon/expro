package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for p:invisible-xml. */
class IxmlStepsTest extends TestSupport {

  private static final String DATE = "date: s?, day, s, month, (s, year)? .\n"
      + "-s: -\" \"+ .\n"
      + "day: digit, digit? .\n"
      + "-digit: [\"0\"-\"9\"] .\n"
      + "month: \"January\"; \"February\"; \"December\" .\n"
      + "year: (digit, digit)?, digit, digit .\n";

  private static String pipeline(String step, String grammar, String source) {
    return "<p:declare-step " + P + "><p:output port='result'/>"
        + "<p:" + step + "><p:with-input port='grammar'>" + grammar + "</p:with-input>"
        + "<p:with-input port='source'><p:inline content-type='text/plain'>" + source
        + "</p:inline></p:with-input></p:" + step.split(" ")[0] + "></p:declare-step>";
  }

  private static String text(String grammar) {
    return "<p:inline content-type='text/plain'>" + grammar + "</p:inline>";
  }

  @Test
  void parseWithTextGrammar() {
    assertEquals("<date><day>31</day><month>December</month><year>2021</year></date>",
        result(pipeline("invisible-xml", text(DATE), "31 December 2021")));
  }

  @Test
  void formerNameIxml() {
    assertEquals("<date><day>1</day><month>January</month></date>",
        result(pipeline("ixml", text(DATE), "1 January")));
  }

  @Test
  void withoutGrammarTheIxmlGrammarIsUsed() {
    String xml = result(pipeline("invisible-xml", "<p:empty/>", "a: \"x\" ."));
    assertEquals("<ixml><rule name=\"a\"><alt><literal string=\"x\"/></alt></rule></ixml>", xml);
  }

  @Test
  void xmlGrammar() {
    String grammar = "<ixml><rule name='a'><alt><literal string='x'/>"
        + "<nonterminal name='b'/></alt></rule><rule name='b'><alt><literal string='y'/>"
        + "</alt></rule></ixml>";
    assertEquals("<a>x<b>y</b></a>", result(pipeline("invisible-xml", grammar, "xy")));
  }

  @Test
  void failedParse() {
    assertError("XC0205", pipeline("invisible-xml", text(DATE), "31 Mumble 2021"));
    String xml = result(pipeline("invisible-xml fail-on-error='false'", text(DATE),
        "31 Mumble 2021"));
    assertTrue(xml.contains("ixml:state=\"failed\""), xml);
  }

  @Test
  void ambiguousParse() {
    String xml = result(pipeline("invisible-xml", text("s: A; B. A: 'x'. B: 'x'."), "x"));
    assertTrue(xml.contains("ixml:state=\"ambiguous\""), xml);
  }

  @Test
  void invalidGrammar() {
    assertError("XC0212", pipeline("invisible-xml", text("a: b ."), "x"));
    assertError("XC0212", pipeline("invisible-xml", text("a: "), "x"));
  }

  @Test
  void moreThanOneGrammar() {
    assertError("XC0211", pipeline("invisible-xml", text(DATE) + text(DATE), "1 January"));
  }
}
