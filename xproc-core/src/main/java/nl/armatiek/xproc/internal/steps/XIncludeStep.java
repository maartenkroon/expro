package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentLoader;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;

/** p:xinclude: XInclude 1.0 processing of an in-memory document. */
final class XIncludeStep implements StepImplementation {

  private static final QName XI_INCLUDE = new QName(Ns.XINCLUDE, "include");
  private static final QName XI_FALLBACK = new QName(Ns.XINCLUDE, "fallback");
  private static final QName XML_BASE = Ns.xml("base");
  private static final QName XML_LANG = Ns.xml("lang");

  private StepContext ctx;
  private boolean fixupBase;
  private boolean fixupLang;

  @Override
  public void run(StepContext ctx) {
    this.ctx = ctx;
    XProcDocument src = ctx.input("source");
    fixupBase = Xdm.bool(ctx.option("fixup-xml-base"));
    fixupLang = Xdm.bool(ctx.option("fixup-xml-lang"));
    XdmNode doc = src.getNode();
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(doc.getBaseURI());
    Set<String> stack = new HashSet<>();
    if (doc.getBaseURI() != null) {
      stack.add(doc.getBaseURI().toString());
    }
    for (XdmNode c : doc.children()) {
      process(tw, c, doc, stack, true);
    }
    ctx.output("result", StepSupport.result(ctx, src, tw.endDocument()));
  }

  private void process(TreeWriter tw, XdmNode n, XdmNode rootDoc, Set<String> stack,
      boolean top) {
    if (n.getNodeKind() != XdmNodeKind.ELEMENT) {
      tw.addSubtree(n);
      return;
    }
    if (n.getNodeName().equals(XI_INCLUDE)) {
      include(tw, n, rootDoc, stack);
      return;
    }
    if (n.getNodeName().equals(XI_FALLBACK)) {
      throw XProcException.xc(29, "xi:fallback must be a child of xi:include");
    }
    tw.addStartElement(n);
    for (XdmNode a : StepSupport.attributes(n)) {
      tw.addAttribute(a);
    }
    for (XdmNode c : n.children()) {
      process(tw, c, rootDoc, stack, false);
    }
    tw.addEndElement();
  }

  private void include(TreeWriter tw, XdmNode inc, XdmNode rootDoc, Set<String> stack) {
    String href = inc.getAttributeValue(new QName("href"));
    String parse = inc.getAttributeValue(new QName("parse"));
    String xpointer = inc.getAttributeValue(new QName("xpointer"));
    String encoding = inc.getAttributeValue(new QName("encoding"));
    if (parse == null) {
      parse = "xml";
    }
    if (!parse.equals("xml") && !parse.equals("text")) {
      throw XProcException.xc(29, "Invalid parse attribute: " + parse);
    }
    if ((href == null || href.isEmpty()) && xpointer == null) {
      throw XProcException.xc(29, "xi:include requires href or xpointer");
    }
    if (parse.equals("text") && xpointer != null) {
      throw XProcException.xc(29, "xpointer is not allowed with parse='text'");
    }
    XdmNode fallback = null;
    for (XdmNode c : inc.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        if (c.getNodeName().equals(XI_FALLBACK)) {
          if (fallback != null) {
            throw XProcException.xc(29, "More than one xi:fallback");
          }
          fallback = c;
        } else if (Ns.XINCLUDE.equals(c.getNodeName().getNamespace())) {
          throw XProcException.xc(29, "Unexpected element in xi:include: " + c.getNodeName());
        }
      }
    }
    try {
      if (parse.equals("text")) {
        URI uri = resolve(inc, href);
        var res = ctx.pc().loader.read(uri);
        Charset cs = encoding == null ? null : DocumentLoader.charsetOf(encoding, 29);
        tw.addText(DocumentLoader.decodeText(res.bytes(), cs));
        return;
      }
      XdmNode target;
      URI uri = null;
      Set<String> inner = new HashSet<>(stack);
      if (href == null || href.isEmpty()) {
        target = rootDoc;
      } else {
        uri = resolve(inc, href);
        String key = uri.toString() + (xpointer == null ? "" : "#" + xpointer);
        if (stack.contains(key) || (xpointer == null && stack.contains(uri.toString()))) {
          throw XProcException.xc(29, "XInclude loop detected: " + uri);
        }
        inner.add(key);
        var res = ctx.pc().loader.read(uri);
        XdmNode loaded = ctx.pc().loader.parseXml(res.bytes(), res.uri(), null, null);
        target = processDocument(loaded, inner);
      }
      List<XdmNode> nodes = new ArrayList<>();
      if (xpointer == null) {
        nodes.add(target);
      } else {
        nodes.addAll(xpointer(target, xpointer));
      }
      boolean sameDoc = uri == null;
      for (XdmNode node : nodes) {
        if (node.getNodeKind() == XdmNodeKind.DOCUMENT) {
          for (XdmNode c : node.children()) {
            emitIncluded(tw, c, inc, sameDoc ? rootDoc : target, inner, sameDoc);
          }
        } else {
          emitIncluded(tw, node, inc, sameDoc ? rootDoc : target, inner, sameDoc);
        }
      }
    } catch (XProcException e) {
      boolean loop = e.getMessage() != null && e.getMessage().startsWith("XInclude loop");
      if (fallback == null || loop) {
        throw e.getCode().getLocalName().equals("XC0029") ? e
            : XProcException.xc(29, "XInclude failed: " + e.getMessage(), e);
      }
      for (XdmNode c : fallback.children()) {
        process(tw, c, rootDoc, stack, false);
      }
    }
  }

  /** Performs XInclude processing on a complete (included) document. */
  private XdmNode processDocument(XdmNode doc, Set<String> stack) {
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(doc.getBaseURI());
    for (XdmNode c : doc.children()) {
      process(tw, c, doc, stack, true);
    }
    return tw.endDocument();
  }

  private void emitIncluded(TreeWriter tw, XdmNode node, XdmNode inc, XdmNode includedDoc,
      Set<String> stack, boolean processIncludes) {
    if (node.getNodeKind() != XdmNodeKind.ELEMENT) {
      tw.addSubtree(node);
      return;
    }
    if (processIncludes && node.getNodeName().equals(XI_INCLUDE)) {
      include(tw, node, includedDoc, stack);
      return;
    }
    tw.addStartElement(node);
    boolean hasBase = false;
    boolean hasLang = false;
    for (XdmNode a : StepSupport.attributes(node)) {
      if (a.getNodeName().equals(XML_BASE) && fixupBase) {
        hasBase = true;
        continue;
      }
      if (a.getNodeName().equals(XML_LANG)) {
        hasLang = true;
      }
      tw.addAttribute(a);
    }
    if (fixupBase && node.getBaseURI() != null) {
      URI parentBase = inc.getParent() == null ? null : inc.getParent().getBaseURI();
      if (hasBase || !node.getBaseURI().equals(parentBase)) {
        tw.addAttribute(XML_BASE, node.getBaseURI().toString());
      }
    }
    if (fixupLang && !hasLang) {
      String lang = langOf(node);
      String incLang = langOf(inc);
      if (lang != null && !lang.equals(incLang)) {
        tw.addAttribute(XML_LANG, lang);
      } else if (lang == null && incLang != null) {
        tw.addAttribute(XML_LANG, "");
      }
    }
    for (XdmNode c : node.children()) {
      if (processIncludes) {
        processIncluded(tw, c, includedDoc, stack);
      } else {
        tw.addSubtree(c);
      }
    }
    tw.addEndElement();
  }

  private void processIncluded(TreeWriter tw, XdmNode n, XdmNode includedDoc,
      Set<String> stack) {
    if (n.getNodeKind() == XdmNodeKind.ELEMENT && n.getNodeName().equals(XI_INCLUDE)) {
      include(tw, n, includedDoc, stack);
      return;
    }
    if (n.getNodeKind() != XdmNodeKind.ELEMENT) {
      tw.addSubtree(n);
      return;
    }
    tw.addStartElement(n);
    for (XdmNode a : StepSupport.attributes(n)) {
      tw.addAttribute(a);
    }
    for (XdmNode c : n.children()) {
      processIncluded(tw, c, includedDoc, stack);
    }
    tw.addEndElement();
  }

  private static String langOf(XdmNode n) {
    for (XdmNode p = n; p != null; p = p.getParent()) {
      if (p.getNodeKind() == XdmNodeKind.ELEMENT) {
        String l = p.getAttributeValue(XML_LANG);
        if (l != null) {
          return l;
        }
      }
    }
    return null;
  }

  private URI resolve(XdmNode inc, String href) {
    if (href.contains("#")) {
      throw XProcException.xc(29, "The href attribute must not contain a fragment identifier");
    }
    URI base = inc.getBaseURI();
    try {
      return base == null ? new URI(href) : base.resolve(new URI(href));
    } catch (java.net.URISyntaxException e) {
      throw XProcException.xc(29, "Invalid href: " + href);
    }
  }

  /** Supports shorthand pointers, element() and xmlns()/xpointer() via XPath. */
  private List<XdmNode> xpointer(XdmNode doc, String ptr) {
    String p = ptr.trim();
    if (!p.contains("(")) {
      return byId(doc, p);
    }
    List<XdmNode> result = new ArrayList<>();
    Map<String, String> ns = new java.util.LinkedHashMap<>();
    int i = 0;
    while (i < p.length()) {
      while (i < p.length() && Character.isWhitespace(p.charAt(i))) {
        i++;
      }
      int open = p.indexOf('(', i);
      if (open < 0) {
        break;
      }
      String scheme = p.substring(i, open).trim();
      StringBuilder data = new StringBuilder();
      int depth = 1;
      int j = open + 1;
      while (j < p.length() && depth > 0) {
        char c = p.charAt(j);
        if (c == '^' && j + 1 < p.length()) {
          data.append(p.charAt(j + 1));
          j += 2;
          continue;
        }
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
          if (depth == 0) {
            break;
          }
        }
        data.append(c);
        j++;
      }
      if (depth != 0) {
        throw XProcException.xc(29, "Malformed XPointer: " + ptr);
      }
      i = j + 1;
      String d = data.toString();
      switch (scheme) {
        case "element" -> {
          result = elementScheme(doc, d);
          if (!result.isEmpty()) {
            return result;
          }
        }
        case "xmlns" -> {
          int eq = d.indexOf('=');
          if (eq > 0) {
            ns.put(d.substring(0, eq).trim(), d.substring(eq + 1).trim());
          }
        }
        case "xpointer", "xpath" -> {
          try {
            XdmValue v = ctx.pc().xpath.evaluateForStep(d, ns, doc.getBaseURI(), doc, null);
            for (var item : v) {
              if (item instanceof XdmNode n) {
                result.add(n);
              }
            }
            if (!result.isEmpty()) {
              return result;
            }
          } catch (XProcException e) {
            // try the next pointer part
          }
        }
        default -> {
          // unknown schemes are skipped
        }
      }
    }
    throw XProcException.xc(29, "XPointer '" + ptr + "' did not identify any nodes");
  }

  private List<XdmNode> byId(XdmNode doc, String id) {
    XdmValue v = ctx.pc().xpath.evaluateForStep("(id($i), //*[@xml:id = $i], //*[@id = $i])[1]",
        Map.of("xml", Ns.XML), null, doc, Map.of(new QName("i"),
            new net.sf.saxon.s9api.XdmAtomicValue(id)));
    List<XdmNode> list = new ArrayList<>();
    for (var item : v) {
      list.add((XdmNode) item);
    }
    if (list.isEmpty()) {
      throw XProcException.xc(29, "No element with id '" + id + "'");
    }
    return list;
  }

  private List<XdmNode> elementScheme(XdmNode doc, String data) {
    String[] parts = data.split("/");
    XdmNode current;
    int start;
    if (!parts[0].isEmpty()) {
      List<XdmNode> byId;
      try {
        byId = byId(doc, parts[0]);
      } catch (XProcException e) {
        return List.of();
      }
      current = byId.get(0);
      start = 1;
    } else {
      current = doc;
      start = 1;
    }
    for (int i = start; i < parts.length; i++) {
      int idx;
      try {
        idx = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        return List.of();
      }
      XdmNode next = null;
      int count = 0;
      for (XdmNode c : current.children()) {
        if (c.getNodeKind() == XdmNodeKind.ELEMENT && ++count == idx) {
          next = c;
          break;
        }
      }
      if (next == null) {
        return List.of();
      }
      current = next;
    }
    return List.of(current);
  }
}
