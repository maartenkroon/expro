package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.util.QNames;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;

/** Helpers shared by step implementations. */
final class StepSupport {

  static final QName P_INDEX = new QName("p", Ns.XPROC, "index");

  private StepSupport() {
  }

  /** Compiles the selection pattern in an option. */
  static XPathExecutable pattern(StepContext ctx, String option) {
    String text = Xdm.string(ctx.option(option));
    return ctx.pc().xpath.compileForStep(text, ctx.optionNamespaces(option), ctx.baseUri(),
        List.of(), true);
  }

  /** Compiles the XPath expression in an option, declaring the given variables. */
  static XPathExecutable expression(StepContext ctx, String option, List<QName> vars) {
    String text = Xdm.string(ctx.option(option));
    Map<String, String> ns = new LinkedHashMap<>(ctx.optionNamespaces(option));
    for (QName v : vars) {
      if (!v.getPrefix().isEmpty()) {
        ns.putIfAbsent(v.getPrefix(), v.getNamespace());
      }
    }
    return ctx.pc().xpath.compileForStep(text, ns, ctx.baseUri(), vars, false);
  }

  static boolean matches(XPathExecutable pattern, XdmNode node) {
    try {
      XPathSelector sel = pattern.load();
      sel.setContextItem(node);
      return sel.effectiveBooleanValue();
    } catch (SaxonApiException e) {
      throw new XProcException(e.getErrorCode() == null ? XProcException.errCode("XD", 83)
          : e.getErrorCode(), e.getMessage(), e);
    }
  }

  static XdmValue evaluate(XPathExecutable exec, XdmItem context, Map<QName, XdmValue> vars) {
    try {
      XPathSelector sel = exec.load();
      if (context != null) {
        sel.setContextItem(context);
      }
      if (vars != null) {
        for (Map.Entry<QName, XdmValue> e : vars.entrySet()) {
          sel.setVariable(e.getKey(), e.getValue());
        }
      }
      return sel.evaluate();
    } catch (SaxonApiException e) {
      throw new XProcException(e.getErrorCode() == null ? XProcException.errCode("XD", 83)
          : e.getErrorCode(), e.getMessage(), e);
    } catch (IllegalStateException | IllegalArgumentException e) {
      // a variable that is not referenced by the expression
      throw new XProcException(XProcException.errCode("XD", 83), e.getMessage(), e);
    }
  }

  /** Evaluates with variables, ignoring variables the expression does not declare. */
  static XdmValue evaluateLenient(XPathExecutable exec, XdmItem context,
      Map<QName, XdmValue> vars) {
    Map<QName, XdmValue> used = new LinkedHashMap<>();
    if (vars != null) {
      List<QName> declared = new ArrayList<>();
      exec.iterateExternalVariables().forEachRemaining(declared::add);
      for (Map.Entry<QName, XdmValue> e : vars.entrySet()) {
        if (declared.contains(e.getKey())) {
          used.put(e.getKey(), e.getValue());
        }
      }
    }
    return evaluate(exec, context, used);
  }

  /** Starts a copy of an element (name, namespaces and attributes). */
  static void startCopy(TreeWriter tw, XdmNode element) {
    tw.addStartElement(element);
    for (XdmNode a : attributes(element)) {
      tw.addAttribute(a);
    }
  }

  static List<XdmNode> attributes(XdmNode element) {
    return element.axisIterator(Axis.ATTRIBUTE).stream().toList();
  }

  /** Builds the result document for a step that transformed the document of 'source'. */
  static XProcDocument result(StepContext ctx, XProcDocument source, XdmNode doc) {
    MediaType ct = isTextOnly(doc) ? MediaType.TEXT : source.getContentType();
    return source.withValue(ctx.processor(), doc, ct);
  }

  static boolean isTextOnly(XdmNode doc) {
    int n = 0;
    for (XdmNode c : doc.children()) {
      if (c.getNodeKind() != XdmNodeKind.TEXT) {
        return false;
      }
      n++;
    }
    return n == 1;
  }

  /** A document containing a c:result element with the given text. */
  static XdmNode cResult(Processor processor, String text, URI base) {
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(base);
    tw.addStartElement(Ns.c("result"));
    tw.addText(text);
    tw.addEndElement();
    return tw.endDocument();
  }

  static String serializeOption(StepContext ctx, XProcDocument doc, String option) {
    Map<QName, XdmValue> params = DocumentSerializer.toParams(ctx.option(option));
    return ctx.pc().serializer.serializeToString(doc, params);
  }

  /** Converts a map(xs:QName, xs:anyAtomicType) option into attribute name/value pairs. */
  static Map<QName, String> attributeMap(StepContext ctx, String option) {
    Map<QName, String> m = new LinkedHashMap<>();
    XdmValue v = ctx.option(option);
    if (v.size() == 0) {
      return m;
    }
    XdmMap map = (XdmMap) v.itemAt(0);
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      QName name = e.getKey().getQNameValue();
      checkAttributeName(name);
      StringBuilder sb = new StringBuilder();
      for (XdmItem i : e.getValue()) {
        if (!sb.isEmpty()) {
          sb.append(' ');
        }
        sb.append(i.getStringValue());
      }
      m.put(name, sb.toString());
    }
    return m;
  }

  static void checkAttributeName(QName name) {
    if ("xmlns".equals(name.getPrefix()) || Ns.XMLNS.equals(name.getNamespace())
        || (name.getNamespace().isEmpty() && "xmlns".equals(name.getLocalName()))) {
      throw XProcException.xc(59, "Invalid attribute name: " + QNames.display(name));
    }
  }

  /** Splits text into lines according to XML end-of-line handling. */
  static List<String> lines(String text) {
    String t = text.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines = new ArrayList<>();
    if (t.isEmpty()) {
      return lines;
    }
    if (t.endsWith("\n")) {
      t = t.substring(0, t.length() - 1);
    }
    int start = 0;
    while (true) {
      int nl = t.indexOf('\n', start);
      if (nl < 0) {
        lines.add(t.substring(start));
        break;
      }
      lines.add(t.substring(start, nl));
      start = nl + 1;
    }
    return lines;
  }

  static String joinLines(List<String> lines) {
    StringBuilder sb = new StringBuilder();
    for (String l : lines) {
      sb.append(l).append('\n');
    }
    return sb.toString();
  }

  /** The text of a text document. */
  static String text(XProcDocument doc) {
    return doc.getNode() == null ? "" : doc.getNode().getStringValue();
  }

  static XProcDocument textResult(StepContext ctx, XProcDocument source, String text) {
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(source.getBaseURI());
    tw.addText(text);
    return source.withValue(ctx.processor(), tw.endDocument(), source.getContentType());
  }

  static boolean isElementOrDocument(XdmNode n) {
    return n.getNodeKind() == XdmNodeKind.ELEMENT || n.getNodeKind() == XdmNodeKind.DOCUMENT;
  }
}
