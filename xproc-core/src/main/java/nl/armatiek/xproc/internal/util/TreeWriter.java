package nl.armatiek.xproc.internal.util;

import java.net.URI;

import net.sf.saxon.event.ComplexContentOutputter;
import net.sf.saxon.expr.parser.Loc;
import net.sf.saxon.event.NamespaceReducer;
import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.event.ReceiverOption;
import net.sf.saxon.om.CopyOptions;
import net.sf.saxon.om.FingerprintedQName;
import net.sf.saxon.om.NamespaceUri;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmSequenceIterator;
import net.sf.saxon.str.StringView;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.tree.tiny.TinyBuilder;
import net.sf.saxon.tree.util.Orphan;
import net.sf.saxon.type.BuiltInAtomicType;
import net.sf.saxon.type.Type;
import net.sf.saxon.type.Untyped;
import nl.armatiek.xproc.XProcException;

/**
 * A convenience wrapper around Saxon's push API for building (document) trees.
 */
public final class TreeWriter {

  private final TinyBuilder builder;
  private final ComplexContentOutputter out;
  private int depth = 0;
  private boolean inStartTag = false;

  public TreeWriter(Processor processor) {
    PipelineConfiguration pipe = processor.getUnderlyingConfiguration().makePipelineConfiguration();
    builder = new TinyBuilder(pipe);
    out = new ComplexContentOutputter(new NamespaceReducer(builder));
  }

  public void startDocument(URI baseUri) {
    String base = baseUri == null ? null : baseUri.toString();
    builder.setSystemId(base);
    builder.setBaseURI(base);
    try {
      out.open();
      out.startDocument(ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  public XdmNode endDocument() {
    try {
      closeStartTag();
      out.endDocument();
      out.close();
    } catch (XPathException e) {
      throw wrap(e);
    }
    return new XdmNode(builder.getCurrentRoot());
  }

  private void closeStartTag() throws XPathException {
    if (inStartTag) {
      out.startContent();
      inStartTag = false;
    }
  }

  public void addStartElement(QName name) {
    try {
      closeStartTag();
      out.startElement(nodeName(name), Untyped.getInstance(), Loc.NONE, ReceiverOption.NONE);
      if (!name.getNamespace().isEmpty() || !name.getPrefix().isEmpty()) {
        out.namespace(name.getPrefix(), NamespaceUri.of(name.getNamespace()), ReceiverOption.NONE);
      }
      inStartTag = true;
      depth++;
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  /**
   * Starts an element with the name and in-scope namespaces of an existing element. Attributes
   * are not copied.
   */
  public void addStartElement(XdmNode element) {
    addStartElement(element, element.getNodeName());
  }

  /** Starts an element with a new name but the in-scope namespaces of an existing element. */
  public void addStartElement(XdmNode element, QName name) {
    NodeInfo ni = element.getUnderlyingNode();
    try {
      closeStartTag();
      String sysId = ni.getSystemId();
      net.sf.saxon.s9api.Location loc = sysId == null ? Loc.NONE : new Loc(sysId,
          ni.getLineNumber(), ni.getColumnNumber());
      out.startElement(nodeName(name), Untyped.getInstance(), loc, ReceiverOption.NONE);
      if (!name.getNamespace().isEmpty() || !name.getPrefix().isEmpty()) {
        out.namespace(name.getPrefix(), NamespaceUri.of(name.getNamespace()),
            ReceiverOption.NONE);
      }
      inStartTag = true;
      depth++;
    } catch (XPathException e) {
      throw wrap(e);
    }
    try {
      out.namespaces(ni.getAllNamespaces(), ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  public void addNamespace(String prefix, String uri) {
    try {
      out.namespace(prefix == null ? "" : prefix, NamespaceUri.of(uri), ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  public void addAttribute(QName name, String value) {
    if (!name.getNamespace().isEmpty() && name.getPrefix().isEmpty()) {
      name = new QName(name.getNamespace().equals(nl.armatiek.xproc.Ns.XML) ? "xml" : "ns1",
          name.getNamespace(), name.getLocalName());
    }
    try {
      out.attribute(nodeName(name), BuiltInAtomicType.UNTYPED_ATOMIC, value, Loc.NONE,
          ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  /** Copies an attribute node. */
  public void addAttribute(XdmNode attr) {
    addAttribute(attr.getNodeName(), attr.getStringValue());
  }

  public void addEndElement() {
    try {
      closeStartTag();
      out.endElement();
      depth--;
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  public void addText(String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    try {
      closeStartTag();
      out.characters(StringView.of(text), Loc.NONE, ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  public void addComment(String text) {
    try {
      closeStartTag();
      out.comment(StringView.of(text), Loc.NONE, ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  public void addPI(String target, String data) {
    try {
      closeStartTag();
      out.processingInstruction(target, StringView.of(data), Loc.NONE, ReceiverOption.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  /**
   * Copies a node. A document node is copied by copying its children; an attribute node is
   * added to the current element.
   */
  public void addSubtree(XdmNode node) {
    try {
      if (node.getNodeKind() == XdmNodeKind.DOCUMENT) {
        XdmSequenceIterator<XdmNode> iter = node.axisIterator(net.sf.saxon.s9api.Axis.CHILD);
        while (iter.hasNext()) {
          addSubtree(iter.next());
        }
        return;
      }
      if (node.getNodeKind() == XdmNodeKind.ATTRIBUTE) {
        addAttribute(node);
        return;
      }
      if (node.getNodeKind() == XdmNodeKind.NAMESPACE) {
        addNamespace(node.getNodeName() == null ? "" : node.getNodeName().getLocalName(),
            node.getStringValue());
        return;
      }
      closeStartTag();
      node.getUnderlyingNode().copy(out, CopyOptions.ALL_NAMESPACES, Loc.NONE);
    } catch (XPathException e) {
      throw wrap(e);
    }
  }

  /** Copies the children of a node. */
  public void addChildren(XdmNode node) {
    XdmSequenceIterator<XdmNode> iter = node.axisIterator(net.sf.saxon.s9api.Axis.CHILD);
    while (iter.hasNext()) {
      addSubtree(iter.next());
    }
  }

  public int getDepth() {
    return depth;
  }

  public boolean isInStartTag() {
    return inStartTag;
  }

  static FingerprintedQName nodeName(QName name) {
    return new FingerprintedQName(name.getPrefix(), NamespaceUri.of(name.getNamespace()),
        name.getLocalName());
  }

  private static XProcException wrap(XPathException e) {
    return XProcException.xd(30, "Failed to construct document: " + e.getMessage(), e);
  }

  /** Creates a parentless text node (used rarely; mainly for constructing values). */
  public static XdmNode orphanText(Processor processor, String text) {
    Orphan o = new Orphan(processor.getUnderlyingConfiguration());
    o.setNodeKind(Type.TEXT);
    o.setStringValue(StringView.of(text));
    return new XdmNode(o);
  }
}
