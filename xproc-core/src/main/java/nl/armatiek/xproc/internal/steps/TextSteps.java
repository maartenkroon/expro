package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.util.Xdm;

/** The text steps (p:text-count, p:text-head, ...). */
final class TextSteps {

  private TextSteps() {
  }

  static void register(StepRegistry r) {
    r.register("text-count", () -> TextSteps::count);
    r.register("text-head", () -> TextSteps::head);
    r.register("text-tail", () -> TextSteps::tail);
    r.register("text-join", () -> TextSteps::join);
    r.register("text-replace", () -> TextSteps::replace);
    r.register("text-sort", () -> TextSteps::sort);
  }

  static void count(StepContext ctx) {
    List<String> lines = StepSupport.lines(StepSupport.text(ctx.input("source")));
    ctx.output("result", XProcDocument.of(ctx.processor(), StepSupport.cResult(ctx.processor(),
        Integer.toString(lines.size()), null), MediaType.XML, (URI) null));
  }

  static void head(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    List<String> lines = StepSupport.lines(StepSupport.text(src));
    long count = Xdm.integer(ctx.option("count"), 0);
    List<String> out;
    if (count > 0) {
      out = lines.subList(0, (int) Math.min(count, lines.size()));
    } else if (count == 0) {
      out = lines;
    } else {
      out = lines.subList((int) Math.min(-count, lines.size()), lines.size());
    }
    ctx.output("result", StepSupport.textResult(ctx, src, StepSupport.joinLines(out)));
  }

  static void tail(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    List<String> lines = StepSupport.lines(StepSupport.text(src));
    long count = Xdm.integer(ctx.option("count"), 0);
    int n = lines.size();
    List<String> out;
    if (count > 0) {
      out = lines.subList((int) Math.max(0, n - count), n);
    } else if (count == 0) {
      out = lines;
    } else {
      out = lines.subList(0, (int) Math.max(0, n + count));
    }
    ctx.output("result", StepSupport.textResult(ctx, src, StepSupport.joinLines(out)));
  }

  static void join(StepContext ctx) {
    String sep = Xdm.string(ctx.option("separator"));
    String prefix = Xdm.string(ctx.option("prefix"));
    String suffix = Xdm.string(ctx.option("suffix"));
    String override = Xdm.string(ctx.option("override-content-type"));
    MediaType ct = MediaType.TEXT;
    if (override != null) {
      ct = MediaType.parse(override);
      if (!ct.isText()) {
        throw XProcException.xc(1, "Not a text media type: " + override);
      }
    }
    StringBuilder sb = new StringBuilder();
    if (prefix != null) {
      sb.append(prefix);
    }
    boolean first = true;
    for (XProcDocument d : ctx.inputs("source")) {
      if (!first && sep != null) {
        sb.append(sep);
      }
      sb.append(StepSupport.text(d));
      first = false;
    }
    if (suffix != null) {
      sb.append(suffix);
    }
    ctx.output("result", XProcDocument.ofText(ctx.processor(), sb.toString(), ct, null));
  }

  static void replace(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    vars.put(new QName("s"), new XdmAtomicValue(StepSupport.text(src)));
    vars.put(new QName("p"), ctx.option("pattern"));
    vars.put(new QName("r"), ctx.option("replacement"));
    String flags = Xdm.string(ctx.option("flags"));
    vars.put(new QName("f"), new XdmAtomicValue(flags == null ? "" : flags));
    String result;
    try {
      result = Xdm.string(ctx.pc().xpath.evaluateForStep("replace($s, $p, $r, $f)", Map.of(),
          null, null, vars));
    } catch (XProcException e) {
      String code = e.getCode().getLocalName();
      if (code.equals("FORX0002")) {
        throw XProcException.xc(147, "Invalid regular expression: " + e.getMessage());
      }
      throw XProcException.xd(30, "p:text-replace failed: " + e.getMessage(), e)
          .setCauseCode(e.getCode());
    }
    ctx.output("result", StepSupport.textResult(ctx, src, result));
  }

  static void sort(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    List<String> lines = StepSupport.lines(StepSupport.text(src));
    String order = Xdm.string(ctx.option("order"));
    String caseOrder = Xdm.string(ctx.option("case-order"));
    String lang = Xdm.string(ctx.option("lang"));
    String collation = Xdm.string(ctx.option("collation"));
    boolean stable = ctx.option("stable").size() == 0 || Xdm.bool(ctx.option("stable"));
    // keys
    XPathExecutable keyExpr = StepSupport.expression(ctx, "sort-key", List.of(
        XmlSteps.DOCS, XmlSteps.POS));
    String text = Xdm.string(ctx.option("sort-key"));
    String wrapped = "let $__l := $Q{" + Ns.EXT + "}docs return $__l ! (if (position() eq $Q{"
        + Ns.EXT + "}pos) then (" + text + "\n) else ())";
    keyExpr = ctx.pc().xpath.compileForStep(wrapped, ctx.optionNamespaces("sort-key"),
        ctx.baseUri(), List.of(XmlSteps.DOCS, XmlSteps.POS), false);
    List<XdmItem> lineItems = new ArrayList<>();
    for (String l : lines) {
      lineItems.add(new XdmAtomicValue(l));
    }
    XdmValue seq = new XdmValue(lineItems);
    List<XdmItem> keys = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      XdmValue k;
      try {
        k = StepSupport.evaluateLenient(keyExpr, null, Map.of(XmlSteps.DOCS, seq, XmlSteps.POS,
            new XdmAtomicValue(i + 1)));
      } catch (XProcException e) {
        throw XProcException.xc(98, "Error evaluating sort key: " + e.getMessage());
      }
      if (k.size() > 1) {
        throw XProcException.xc(99, "The sort key returned more than one item");
      }
      keys.add(k.size() == 0 ? new XdmAtomicValue("") : k.itemAt(0));
    }
    // Delegate the sort to XPath (fn:sort with a collation)
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    List<XdmItem> idx = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      idx.add(new XdmAtomicValue(i + 1));
    }
    vars.put(new QName("idx"), new XdmValue(idx));
    vars.put(new QName("keys"), new XdmValue(keys));
    String coll = collation;
    if ("http://www.w3.org/2005/xpath-functions/collation/codepoint".equals(coll)
        && (lang != null || caseOrder != null)) {
      coll = null;
    }
    if (coll == null && (lang != null || caseOrder != null)) {
      coll = "http://www.w3.org/2013/collation/UCA?" + (lang == null ? "" : "lang=" + lang + ";")
          + (caseOrder == null ? "" : "caseFirst=" + (caseOrder.equals("upper-first") ? "upper"
              : "lower"));
    }
    if (coll == null) {
      coll = "http://www.w3.org/2005/xpath-functions/collation/codepoint";
    }
    if (!coll.equals("http://www.w3.org/2005/xpath-functions/collation/codepoint")
        && !coll.startsWith("http://www.w3.org/2013/collation/UCA")
        && !coll.startsWith("http://www.w3.org/2005/xpath-functions/collation/html-ascii")) {
      throw XProcException.xc(98, "Unsupported collation: " + coll);
    }
    if (caseOrder != null || lang != null) {
      List<String> out = javaSort(lines, keys, lang, caseOrder, "descending".equals(order));
      ctx.output("result", StepSupport.textResult(ctx, src, StepSupport.joinLines(out)));
      return;
    }
    vars.put(new QName("c"), new XdmAtomicValue(coll));
    XdmValue sorted;
    try {
      sorted = ctx.pc().xpath.evaluateForStep("sort($idx, $c, function($i) { $keys[$i] })",
          Map.of(), null, null, vars);
    } catch (XProcException e) {
      throw XProcException.xc(98, "Error sorting lines: " + e.getMessage());
    }
    List<String> out = new ArrayList<>();
    for (XdmItem i : sorted) {
      out.add(lines.get(Integer.parseInt(i.getStringValue()) - 1));
    }
    if ("descending".equals(order)) {
      java.util.Collections.reverse(out);
      if (stable) {
        // keep the original order of equal keys
        out = stableDescending(lines, keys, sorted);
      }
    }
    ctx.output("result", StepSupport.textResult(ctx, src, StepSupport.joinLines(out)));
  }

  private static List<String> javaSort(List<String> lines, List<XdmItem> keys, String lang,
      String caseOrder, boolean descending) {
    java.util.Locale locale = lang == null ? java.util.Locale.getDefault()
        : java.util.Locale.forLanguageTag(lang);
    java.text.Collator coll = java.text.Collator.getInstance(locale);
    coll.setStrength(java.text.Collator.SECONDARY);
    java.text.Collator tertiary = java.text.Collator.getInstance(locale);
    tertiary.setStrength(java.text.Collator.TERTIARY);
    boolean upperFirst = !"lower-first".equals(caseOrder);
    List<Integer> idx = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      idx.add(i);
    }
    java.util.Comparator<Integer> cmp = (a, b) -> {
      String ka = keys.get(a).getStringValue();
      String kb = keys.get(b).getStringValue();
      int c = coll.compare(ka, kb);
      if (c != 0) {
        return c;
      }
      if (caseOrder == null) {
        return tertiary.compare(ka, kb);
      }
      for (int i = 0; i < Math.min(ka.length(), kb.length()); i++) {
        char x = ka.charAt(i);
        char y = kb.charAt(i);
        if (x != y && Character.toLowerCase(x) == Character.toLowerCase(y)) {
          boolean xu = Character.isUpperCase(x);
          return (xu == upperFirst) ? -1 : 1;
        }
      }
      return 0;
    };
    idx.sort(descending ? cmp.reversed() : cmp);
    List<String> out = new ArrayList<>();
    for (int i : idx) {
      out.add(lines.get(i));
    }
    return out;
  }

  private static List<String> stableDescending(List<String> lines, List<XdmItem> keys,
      XdmValue ascending) {
    List<Integer> order = new ArrayList<>();
    for (XdmItem i : ascending) {
      order.add(Integer.parseInt(i.getStringValue()) - 1);
    }
    List<List<Integer>> groups = new ArrayList<>();
    for (int i : order) {
      if (!groups.isEmpty()) {
        List<Integer> last = groups.get(groups.size() - 1);
        if (keys.get(last.get(0)).getStringValue().equals(keys.get(i).getStringValue())) {
          last.add(i);
          continue;
        }
      }
      List<Integer> g = new ArrayList<>();
      g.add(i);
      groups.add(g);
    }
    List<String> out = new ArrayList<>();
    for (int g = groups.size() - 1; g >= 0; g--) {
      for (int i : groups.get(g)) {
        out.add(lines.get(i));
      }
    }
    return out;
  }
}
