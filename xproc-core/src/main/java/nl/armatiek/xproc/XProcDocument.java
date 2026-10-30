package nl.armatiek.xproc;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.internal.util.TreeWriter;

/**
 * A document flowing through a pipeline: a representation plus its document properties.
 * <p>
 * XML, HTML, and text documents are represented by a document node; JSON documents by a map,
 * array, atomic value or the empty sequence (JSON {@code null}); other ("binary") documents by
 * a byte array (their XDM representation is an empty document node).
 * <p>
 * Instances are immutable.
 */
public final class XProcDocument {

  private final XdmValue value;
  private final byte[] binary;
  private final MediaType contentType;
  private final Map<QName, XdmValue> properties;

  private XProcDocument(XdmValue value, byte[] binary, MediaType contentType,
      Map<QName, XdmValue> properties) {
    this.value = value;
    this.binary = binary;
    this.contentType = contentType;
    this.properties = Collections.unmodifiableMap(properties);
  }

  /**
   * Creates a document from an XDM value.
   *
   * @param processor the Saxon processor (used to rebuild the tree if the base URI must change)
   * @param value a document node (XML, HTML, text) or JSON value
   * @param contentType the content type, or null to use the content-type property or the
   *        default for the kind of value
   * @param props the document properties (may be null)
   */
  public static XProcDocument of(Processor processor, XdmValue value, MediaType contentType,
      Map<QName, XdmValue> props) {
    Map<QName, XdmValue> p = new LinkedHashMap<>();
    if (props != null) {
      p.putAll(props);
    }
    MediaType ct = contentType;
    if (ct == null && p.containsKey(Ns.CONTENT_TYPE)) {
      ct = MediaType.parse(p.get(Ns.CONTENT_TYPE).itemAt(0).getStringValue());
    }
    if (ct == null) {
      if (value instanceof XdmNode node) {
        ct = isTextDocument(node) ? MediaType.TEXT : MediaType.XML;
      } else {
        ct = MediaType.JSON;
      }
    }
    p.put(Ns.CONTENT_TYPE, new XdmAtomicValue(ct.toString()));
    normalizeSerialization(p);
    XdmValue v = value;
    if (v instanceof XdmNode node && ct.getKind() != MediaType.Kind.JSON) {
      if (node.getNodeKind() != XdmNodeKind.DOCUMENT) {
        v = node = wrapInDocument(processor, node);
      }
      URI nodeBase = node.getBaseURI();
      if (p.containsKey(Ns.BASE_URI)) {
        XdmValue bv = p.get(Ns.BASE_URI);
        URI base = bv.size() == 0 ? null : URI.create(bv.itemAt(0).getStringValue());
        if (base == null) {
          p.remove(Ns.BASE_URI);
        } else {
          p.put(Ns.BASE_URI, new XdmAtomicValue(base));
        }
        if (!Objects.equals(base, nodeBase)) {
          v = rebase(processor, node, base);
        }
      } else if (nodeBase != null && !nodeBase.toString().isEmpty()) {
        p.put(Ns.BASE_URI, new XdmAtomicValue(nodeBase));
      }
    } else if (p.containsKey(Ns.BASE_URI)) {
      XdmValue bv = p.get(Ns.BASE_URI);
      if (bv.size() == 0) {
        p.remove(Ns.BASE_URI);
      } else {
        p.put(Ns.BASE_URI, new XdmAtomicValue(URI.create(bv.itemAt(0).getStringValue())));
      }
    }
    return new XProcDocument(v, null, ct, p);
  }

  /** Creates a document from an XDM value with only a base URI as additional property. */
  public static XProcDocument of(Processor processor, XdmValue value, MediaType contentType,
      URI baseUri) {
    Map<QName, XdmValue> p = new LinkedHashMap<>();
    if (baseUri != null) {
      p.put(Ns.BASE_URI, new XdmAtomicValue(baseUri));
    }
    return of(processor, value, contentType, p);
  }

  /** Creates a binary (other) document. */
  public static XProcDocument ofBinary(Processor processor, byte[] data, MediaType contentType,
      Map<QName, XdmValue> props) {
    Map<QName, XdmValue> p = new LinkedHashMap<>();
    if (props != null) {
      p.putAll(props);
    }
    MediaType ct = contentType == null ? MediaType.OCTET_STREAM : contentType;
    p.put(Ns.CONTENT_TYPE, new XdmAtomicValue(ct.toString()));
    normalizeSerialization(p);
    URI base = null;
    if (p.containsKey(Ns.BASE_URI)) {
      XdmValue bv = p.get(Ns.BASE_URI);
      if (bv.size() == 0) {
        p.remove(Ns.BASE_URI);
      } else {
        base = URI.create(bv.itemAt(0).getStringValue());
        p.put(Ns.BASE_URI, new XdmAtomicValue(base));
      }
    }
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(base);
    XdmNode empty = tw.endDocument();
    return new XProcDocument(empty, data, ct, p);
  }

  /** Creates a text document. */
  public static XProcDocument ofText(Processor processor, String text, MediaType contentType,
      URI baseUri) {
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(baseUri);
    tw.addText(text);
    return of(processor, tw.endDocument(), contentType == null ? MediaType.TEXT : contentType,
        baseUri);
  }

  /** Returns a copy of this document with a new set of properties. */
  public XProcDocument withProperties(Processor processor, Map<QName, XdmValue> props) {
    Map<QName, XdmValue> p = new LinkedHashMap<>(props);
    MediaType ct = contentType;
    if (p.containsKey(Ns.CONTENT_TYPE)) {
      ct = MediaType.parse(p.get(Ns.CONTENT_TYPE).itemAt(0).getStringValue());
    }
    if (binary != null) {
      return ofBinary(processor, binary, ct, p);
    }
    if (!p.containsKey(Ns.BASE_URI) && value instanceof XdmNode node
        && node.getBaseURI() != null && !node.getBaseURI().toString().isEmpty()) {
      // explicit removal of the base URI
      p.put(Ns.BASE_URI, XdmEmptySequence.getInstance());
    }
    return of(processor, value, ct, p);
  }

  /** Returns a copy of this document with one property changed (or removed if value is null). */
  public XProcDocument withProperty(Processor processor, QName name, XdmValue v) {
    Map<QName, XdmValue> p = new LinkedHashMap<>(properties);
    if (v == null) {
      p.remove(name);
    } else {
      p.put(name, v);
    }
    return withProperties(processor, p);
  }

  /** Returns a document with the same properties (except content type) but a new value. */
  public XProcDocument withValue(Processor processor, XdmValue newValue, MediaType ct) {
    Map<QName, XdmValue> p = new LinkedHashMap<>(properties);
    p.remove(Ns.CONTENT_TYPE);
    if (ct != null && !ct.toBaseString().equals(contentType.toBaseString())) {
      p.remove(Ns.SERIALIZATION);
    }
    return of(processor, newValue, ct, p);
  }

  /** The XDM representation of the document. */
  public XdmValue getValue() {
    return value;
  }

  /** The XDM representation as a single item, or null for the JSON null value. */
  public XdmItem getItem() {
    return value.size() == 0 ? null : value.itemAt(0);
  }

  /** The document node for XML, HTML, text (and binary) documents; null for JSON documents. */
  public XdmNode getNode() {
    return value instanceof XdmNode n ? n : null;
  }

  /** The bytes of a binary document, or null if this is not a binary document. */
  public byte[] getBinary() {
    return binary;
  }

  public boolean isBinary() {
    return binary != null;
  }

  public MediaType getContentType() {
    return contentType;
  }

  public MediaType.Kind getKind() {
    return binary != null ? MediaType.Kind.OTHER : contentType.getKind();
  }

  /** The base URI property, or null. */
  public URI getBaseURI() {
    XdmValue v = properties.get(Ns.BASE_URI);
    if (v == null || v.size() == 0) {
      return null;
    }
    return URI.create(v.itemAt(0).getStringValue());
  }

  /** The document properties. */
  public Map<QName, XdmValue> getProperties() {
    return properties;
  }

  public XdmValue getProperty(QName name) {
    return properties.get(name);
  }

  /** The document properties as an XDM map. */
  public XdmMap getPropertiesMap() {
    Map<XdmAtomicValue, XdmValue> m = new LinkedHashMap<>();
    for (Map.Entry<QName, XdmValue> e : properties.entrySet()) {
      m.put(new XdmAtomicValue(e.getKey()), e.getValue());
    }
    return new XdmMap(m);
  }

  /** The serialization property (may be empty). */
  public Map<QName, XdmValue> getSerializationProperties() {
    XdmValue v = properties.get(Ns.SERIALIZATION);
    Map<QName, XdmValue> result = new LinkedHashMap<>();
    if (v != null && v.size() == 1 && v.itemAt(0) instanceof XdmMap map) {
      for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
        XdmAtomicValue k = e.getKey();
        QName qn = k.getValue() instanceof net.sf.saxon.value.QNameValue
            ? k.getQNameValue() : new QName(k.getStringValue());
        result.put(qn, e.getValue());
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return "XProcDocument[" + contentType + (getBaseURI() == null ? "" : ", " + getBaseURI())
        + "]";
  }

  /** Checks and normalizes the serialization property (keys become QNames). */
  private static void normalizeSerialization(Map<QName, XdmValue> p) {
    XdmValue v = p.get(Ns.SERIALIZATION);
    if (v == null) {
      return;
    }
    if (v.size() != 1 || !(v.itemAt(0) instanceof XdmMap map)) {
      throw XProcException.xd(70, "The serialization property must be a map");
    }
    Map<XdmAtomicValue, XdmValue> m = new LinkedHashMap<>();
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      XdmAtomicValue k = e.getKey();
      if (k.getUnderlyingValue() instanceof net.sf.saxon.value.QNameValue) {
        m.put(k, e.getValue());
        continue;
      }
      if (!(k.getUnderlyingValue() instanceof net.sf.saxon.value.StringValue)) {
        throw XProcException.xd(70, "Invalid key in serialization property: " + k);
      }
      try {
        m.put(new XdmAtomicValue(nl.armatiek.xproc.internal.util.QNames.parseEQName(
            k.getStringValue(), Map.of(), "XD", 70, 70)), e.getValue());
      } catch (XProcException ex) {
        throw XProcException.xd(70, "Invalid key in serialization property: " + k);
      }
    }
    p.put(Ns.SERIALIZATION, new XdmMap(m));
  }

  static boolean isTextDocument(XdmNode node) {
    if (node.getNodeKind() == XdmNodeKind.TEXT) {
      return true;
    }
    if (node.getNodeKind() != XdmNodeKind.DOCUMENT) {
      return false;
    }
    int count = 0;
    for (XdmNode child : node.children()) {
      if (child.getNodeKind() != XdmNodeKind.TEXT) {
        return false;
      }
      count++;
    }
    return count == 1;
  }

  private static XdmNode wrapInDocument(Processor processor, XdmNode node) {
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(node.getBaseURI());
    tw.addSubtree(node);
    return tw.endDocument();
  }

  private static XdmNode rebase(Processor processor, XdmNode doc, URI base) {
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(base);
    tw.addChildren(doc);
    return tw.endDocument();
  }
}
