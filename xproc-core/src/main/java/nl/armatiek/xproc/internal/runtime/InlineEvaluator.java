package nl.armatiek.xproc.internal.runtime;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmFunctionItem;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.io.DocumentLoader;
import nl.armatiek.xproc.internal.model.Binding;
import nl.armatiek.xproc.internal.model.ValueTemplate;
import nl.armatiek.xproc.internal.model.XPathExpr;
import nl.armatiek.xproc.internal.util.TreeWriter;

/** Produces the document described by a p:inline (explicit or implicit). */
final class InlineEvaluator {

  private final ProcessorContext pc;

  InlineEvaluator(ProcessorContext pc) {
    this.pc = pc;
  }

  /**
   * @param inline the inline binding
   * @param evaluator evaluates an expression of a value template
   * @param docProps the evaluated document-properties (may be null)
   */
  XProcDocument evaluate(Binding.Inline inline, Function<XPathExpr, XdmValue> evaluator,
      Map<QName, XdmValue> docProps) {
    MediaType ct = inline.contentType() == null ? MediaType.XML
        : MediaType.parse(inline.contentType());
    Map<QName, XdmValue> props = new LinkedHashMap<>();
    if (docProps != null) {
      props.putAll(docProps);
    }
    if (props.containsKey(Ns.CONTENT_TYPE)) {
      MediaType pct = MediaType.parse(props.get(Ns.CONTENT_TYPE).itemAt(0).getStringValue());
      if (!pct.toBaseString().equals(ct.toBaseString())) {
        throw XProcException.xd(62, "The content-type document property '" + pct
            + "' differs from the content type '" + ct + "'");
      }
      props.remove(Ns.CONTENT_TYPE);
    }
    URI base = inline.implicit() ? baseOf(inline.element().getParent())
        : baseOf(inline.element());
    if (props.containsKey(Ns.BASE_URI)) {
      XdmValue bv = props.get(Ns.BASE_URI);
      if (bv.size() > 0) {
        String b = bv.itemAt(0).getStringValue();
        try {
          URI u = new URI(b);
          if (!u.isAbsolute()) {
            throw XProcException.xd(64, "Base URI is not absolute: " + b);
          }
          base = u;
        } catch (java.net.URISyntaxException e) {
          throw XProcException.xd(64, "Invalid base URI: " + b);
        }
      }
    } else if (base != null) {
      props.put(Ns.BASE_URI, new XdmAtomicValue(base));
    }

    String encoding = inline.encoding();
    if (encoding != null) {
      if (ct.isMarkup()) {
        throw XProcException.xd(54, "An encoding cannot be specified for XML or HTML content");
      }
      if (!"base64".equals(encoding)) {
        throw XProcException.xs(69, "Unsupported encoding: " + encoding);
      }
      String text = textContent(inline, 56);
      byte[] bytes;
      try {
        bytes = Base64.getMimeDecoder().decode(text.trim());
      } catch (IllegalArgumentException e) {
        throw XProcException.xd(40, "Content is not correctly base64 encoded");
      }
      Charset cs = null;
      if (ct.getCharset() != null) {
        cs = DocumentLoader.charsetOf(ct.getCharset(), 39);
      }
      return pc.loader.parse(bytes, ct, cs == null ? StandardCharsets.UTF_8 : cs, base, null,
          props, false);
    }
    if (ct.getCharset() != null) {
      throw XProcException.xd(55, "A charset is specified but no encoding: " + ct);
    }

    if (ct.isMarkup()) {
      TreeWriter tw = new TreeWriter(pc.processor);
      tw.startDocument(base);
      if (inline.implicit()) {
        copy(tw, inline.element(), inline, evaluator, true);
      } else {
        for (XdmNode child : inline.element().children()) {
          copy(tw, child, inline, evaluator, true);
        }
      }
      return XProcDocument.of(pc.processor, tw.endDocument(), ct, props);
    }

    // text, JSON, other
    if (inline.implicit()) {
      throw XProcException.xd(63, "Inline content with markup must have an XML or HTML type");
    }
    StringBuilder sb = new StringBuilder();
    for (XdmNode child : inline.element().children()) {
      switch (child.getNodeKind()) {
        case TEXT -> {
          ValueTemplate t = inline.templates() == null ? null
              : inline.templates().byNode.get(child);
          if (t == null) {
            sb.append(child.getStringValue());
          } else {
            for (Object part : t.parts) {
              if (part instanceof String s) {
                sb.append(s);
              } else {
                XdmValue v = evaluator.apply((XPathExpr) part);
                checkTemplateValue(v, false);
                sb.append(pc.serializer.serializeAsText(v));
              }
            }
          }
        }
        case COMMENT, PROCESSING_INSTRUCTION -> {
          throw XProcException.xd(63,
              "Inline content with markup must have an XML or HTML type");
        }
        case ELEMENT -> {
          if (isDocumentation(child)) {
            continue;
          }
          throw XProcException.xd(63,
              "Inline content with markup must have an XML or HTML type");
        }
        default -> {
        }
      }
    }
    String text = sb.toString();
    if (ct.isText()) {
      XProcDocument d = XProcDocument.ofText(pc.processor, text, ct, base);
      return d.withProperties(pc.processor, mergeProps(d.getProperties(), props));
    }
    if (ct.isJson()) {
      XdmValue v = pc.loader.parseJson(text, null);
      return XProcDocument.of(pc.processor, v, ct, props);
    }
    return XProcDocument.ofBinary(pc.processor, text.getBytes(StandardCharsets.UTF_8), ct,
        props);
  }

  private static boolean isDocumentation(XdmNode n) {
    QName name = n.getNodeName();
    return Ns.XPROC.equals(name.getNamespace())
        && ("documentation".equals(name.getLocalName()) || "pipeinfo".equals(name.getLocalName()));
  }

  private static Map<QName, XdmValue> mergeProps(Map<QName, XdmValue> a,
      Map<QName, XdmValue> b) {
    Map<QName, XdmValue> m = new LinkedHashMap<>(a);
    m.putAll(b);
    return m;
  }

  private static URI baseOf(XdmNode node) {
    if (node == null) {
      return null;
    }
    try {
      URI u = node.getBaseURI();
      return u == null || u.toString().isEmpty() ? null : u;
    } catch (IllegalStateException e) {
      throw XProcException.xd(64, "Invalid base URI: " + e.getMessage());
    }
  }

  private String textContent(Binding.Inline inline, int markupError) {
    StringBuilder sb = new StringBuilder();
    if (inline.implicit()) {
      throw XProcException.xd(markupError, "Encoded content must not contain markup");
    }
    for (XdmNode child : inline.element().children()) {
      if (child.getNodeKind() == XdmNodeKind.TEXT) {
        sb.append(child.getStringValue());
      } else if (child.getNodeKind() == XdmNodeKind.ELEMENT && isDocumentation(child)) {
        continue;
      } else {
        throw XProcException.xd(markupError, "Encoded content must not contain markup");
      }
    }
    return sb.toString();
  }

  private void copy(TreeWriter tw, XdmNode node, Binding.Inline inline,
      Function<XPathExpr, XdmValue> evaluator, boolean top) {
    Binding.InlineTemplates templates = inline.templates();
    switch (node.getNodeKind()) {
      case ELEMENT -> {
        if (templates != null && templates.excluded.contains(node)) {
          return;
        }
        tw.addStartElement(node.getNodeName());
        for (XdmNode ns : node.axisIterator(Axis.NAMESPACE).stream().toList()) {
          String prefix = ns.getNodeName() == null ? "" : ns.getNodeName().getLocalName();
          String uri = ns.getStringValue();
          if ("xml".equals(prefix) || inline.excludedNamespaces().contains(uri)) {
            continue;
          }
          tw.addNamespace(prefix, uri);
        }
        for (XdmNode attr : node.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
          if (templates != null && templates.dropped.contains(attr)) {
            continue;
          }
          ValueTemplate t = templates == null ? null : templates.byNode.get(attr);
          if (t == null) {
            tw.addAttribute(attr);
          } else {
            StringBuilder sb = new StringBuilder();
            for (Object part : t.parts) {
              if (part instanceof String s) {
                sb.append(s);
              } else {
                XdmValue v = evaluator.apply((XPathExpr) part);
                sb.append(atomizeToString(v));
              }
            }
            tw.addAttribute(attr.getNodeName(), sb.toString());
          }
        }
        for (XdmNode child : node.children()) {
          copy(tw, child, inline, evaluator, false);
        }
        tw.addEndElement();
      }
      case TEXT -> {
        ValueTemplate t = templates == null ? null : templates.byNode.get(node);
        if (t == null) {
          tw.addText(node.getStringValue());
        } else {
          insertTemplate(tw, t, evaluator);
        }
      }
      case COMMENT -> tw.addComment(node.getStringValue());
      case PROCESSING_INSTRUCTION -> tw.addPI(node.getNodeName().getLocalName(),
          node.getStringValue());
      default -> {
      }
    }
  }

  private void insertTemplate(TreeWriter tw, ValueTemplate t,
      Function<XPathExpr, XdmValue> evaluator) {
    boolean onlyAttributesSoFar = tw.isInStartTag();
    for (Object part : t.parts) {
      if (part instanceof String s) {
        if (!s.isEmpty()) {
          tw.addText(s);
          onlyAttributesSoFar = false;
        }
        continue;
      }
      XdmValue v = evaluator.apply((XPathExpr) part);
      checkTemplateValue(v, true);
      List<String> pendingAtomics = new ArrayList<>();
      for (XdmItem item : v) {
        if (item instanceof XdmNode n) {
          if (!pendingAtomics.isEmpty()) {
            tw.addText(String.join(" ", pendingAtomics));
            pendingAtomics.clear();
            onlyAttributesSoFar = false;
          }
          if (n.getNodeKind() == XdmNodeKind.ATTRIBUTE) {
            if (!onlyAttributesSoFar) {
              throw XProcException.xd(52, "An attribute node cannot be inserted here");
            }
            tw.addAttribute(n);
          } else {
            tw.addSubtree(n);
            onlyAttributesSoFar = false;
          }
        } else {
          pendingAtomics.add(item.getStringValue());
        }
      }
      if (!pendingAtomics.isEmpty()) {
        tw.addText(String.join(" ", pendingAtomics));
        onlyAttributesSoFar = false;
      }
    }
  }

  private static void checkTemplateValue(XdmValue v, boolean markup) {
    for (XdmItem item : v) {
      if (item instanceof XdmFunctionItem) {
        throw XProcException.xd(51, "A value template must not produce maps, arrays or functions");
      }
      if (!markup && item instanceof XdmNode n && n.getNodeKind() == XdmNodeKind.ATTRIBUTE) {
        throw XProcException.xd(52, "An attribute node cannot be inserted in non-XML content");
      }
    }
  }

  static String atomizeToString(XdmValue v) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (XdmItem item : v) {
      if (item instanceof XdmFunctionItem) {
        throw XProcException.xd(51, "A value template must not produce maps, arrays or functions");
      }
      if (!first) {
        sb.append(' ');
      }
      sb.append(item.getStringValue());
      first = false;
    }
    return sb.toString();
  }
}
