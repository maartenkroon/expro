package nl.armatiek.xproc.internal.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.value.QNameValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;

/** Serializes documents according to XProc's serialization rules. */
public final class DocumentSerializer {

  private final Processor processor;

  public DocumentSerializer(Processor processor) {
    this.processor = processor;
  }

  /**
   * Merges serialization parameters: the document's serialization property takes precedence
   * over the parameters specified on the step/port.
   */
  public static Map<QName, XdmValue> merge(Map<QName, XdmValue> stepParams,
      XProcDocument doc) {
    Map<QName, XdmValue> m = new LinkedHashMap<>();
    if (stepParams != null) {
      m.putAll(stepParams);
    }
    if (doc != null) {
      m.putAll(doc.getSerializationProperties());
    }
    return m;
  }

  /** Converts an XDM map (map(xs:QName, item()*)) into a Java map. */
  public static Map<QName, XdmValue> toParams(XdmValue value) {
    Map<QName, XdmValue> m = new LinkedHashMap<>();
    if (value == null || value.size() == 0) {
      return m;
    }
    if (!(value.itemAt(0) instanceof XdmMap map)) {
      throw XProcException.xd(70, "Serialization parameters must be a map");
    }
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      XdmAtomicValue k = e.getKey();
      QName qn = k.getUnderlyingValue() instanceof QNameValue ? k.getQNameValue()
          : new QName(k.getStringValue());
      m.put(qn, e.getValue());
    }
    return m;
  }

  public void serialize(XProcDocument doc, OutputStream out, Map<QName, XdmValue> stepParams) {
    try {
      if (doc.isBinary()) {
        out.write(doc.getBinary());
        return;
      }
      Map<QName, XdmValue> params = merge(stepParams, doc);
      Serializer ser = processor.newSerializer(out);
      configure(ser, doc.getContentType(), params);
      ser.serializeXdmValue(doc.getValue());
    } catch (IOException e) {
      throw XProcException.xd(30, "Failed to write document: " + e.getMessage(), e);
    } catch (SaxonApiException e) {
      QName code = e.getErrorCode();
      if (code != null && code.getLocalName().startsWith("SE")) {
        throw XProcException.xd(20, "Serialization failed: " + e.getMessage(), e)
            .setCauseCode(code);
      }
      throw XProcException.xd(20, "Serialization failed: " + e.getMessage(), e);
    }
  }

  public byte[] serializeToBytes(XProcDocument doc, Map<QName, XdmValue> stepParams) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    serialize(doc, bos, stepParams);
    return bos.toByteArray();
  }

  public String serializeToString(XProcDocument doc, Map<QName, XdmValue> stepParams) {
    Map<QName, XdmValue> params = merge(stepParams, doc);
    String enc = params.containsKey(new QName("encoding"))
        ? params.get(new QName("encoding")).itemAt(0).getStringValue() : "UTF-8";
    return new String(serializeToBytes(doc, stepParams), java.nio.charset.Charset.forName(enc));
  }

  /** Serializes an arbitrary XDM value with the text method (used for text value templates). */
  public String serializeAsText(XdmValue value) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    Serializer ser = processor.newSerializer(bos);
    ser.setOutputProperty(Serializer.Property.METHOD, "text");
    ser.setOutputProperty(Serializer.Property.ENCODING, "UTF-8");
    ser.setOutputProperty(Serializer.Property.ITEM_SEPARATOR, " ");
    try {
      ser.serializeXdmValue(value);
    } catch (SaxonApiException e) {
      throw XProcException.xd(30, "Serialization failed: " + e.getMessage(), e);
    }
    return bos.toString(StandardCharsets.UTF_8);
  }

  /** Default method for a content type. */
  public static String defaultMethod(MediaType ct) {
    if (ct.isHtml()) {
      return "application".equals(ct.getType()) ? "xhtml" : "html";
    }
    if (ct.isXml()) {
      return "xml";
    }
    if (ct.isJson()) {
      return "json";
    }
    if (ct.isText()) {
      return "text";
    }
    return "xml";
  }

  public void configure(Serializer ser, MediaType ct, Map<QName, XdmValue> params) {
    String method = null;
    QName methodName = new QName("method");
    if (params.containsKey(methodName)) {
      method = valueString(methodName, params.get(methodName));
    }
    if (method == null) {
      method = defaultMethod(ct);
    }
    ser.setOutputProperty(Serializer.Property.METHOD, method);
    if ("xml".equals(method) || "html".equals(method) || "xhtml".equals(method)) {
      if ("xml".equals(method)) {
        ser.setOutputProperty(Serializer.Property.VERSION, "1.0");
      }
      ser.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "no");
    }
    ser.setOutputProperty(Serializer.Property.ENCODING, "UTF-8");
    for (Map.Entry<QName, XdmValue> e : params.entrySet()) {
      QName name = e.getKey();
      if (name.equals(methodName)) {
        continue;
      }
      String v = valueString(name, e.getValue());
      if (v == null) {
        continue;
      }
      try {
        ser.setOutputProperty(name, v);
      } catch (IllegalArgumentException ex) {
        if (name.getNamespace().isEmpty()) {
          throw XProcException.xd(20, "Invalid serialization parameter " + name + ": "
              + ex.getMessage());
        }
        // unknown extension parameters are ignored
      }
    }
  }

  private static String valueString(QName name, XdmValue value) {
    if (value == null || value.size() == 0) {
      return null;
    }
    if (value.size() == 1 && value.itemAt(0) instanceof XdmMap) {
      // e.g. use-character-maps: not supported as a simple property
      return null;
    }
    StringJoiner sj = new StringJoiner(" ");
    for (XdmItem item : value) {
      if (item instanceof XdmAtomicValue av) {
        Object u = av.getUnderlyingValue();
        if (u instanceof QNameValue qv) {
          String uri = qv.getNamespaceURI().toString();
          sj.add(uri.isEmpty() ? qv.getLocalName() : "{" + uri + "}" + qv.getLocalName());
          continue;
        }
        if (u instanceof net.sf.saxon.value.BooleanValue bv) {
          sj.add(bv.getBooleanValue() ? "yes" : "no");
          continue;
        }
      }
      String s = item.getStringValue();
      sj.add(s);
    }
    String s = sj.toString();
    switch (name.getLocalName()) {
      case "indent", "omit-xml-declaration", "byte-order-mark", "escape-uri-attributes",
          "include-content-type", "undeclare-prefixes", "allow-duplicate-names" -> {
        if ("true".equals(s) || "1".equals(s)) {
          return "yes";
        }
        if ("false".equals(s) || "0".equals(s)) {
          return "no";
        }
        return s;
      }
      default -> {
        return s;
      }
    }
  }
}
