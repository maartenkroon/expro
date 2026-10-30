package nl.armatiek.xproc.internal.xpath;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.value.AnyURIValue;
import net.sf.saxon.value.AtomicValue;
import net.sf.saxon.value.QNameValue;
import net.sf.saxon.value.StringValue;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.util.QNames;

/**
 * Converts values of variables and options to their declared sequence type, applying the XPath
 * function conversion rules plus the XProc implicit casting rules for QNames, URIs, and maps with
 * QName keys (XProc 3.1, section 11.5).
 */
public final class TypeConverter {

  private final XPathEngine xpath;

  public TypeConverter(XPathEngine xpath) {
    this.xpath = xpath;
  }

  /** Checks that a sequence type is syntactically valid (err:XS0096). */
  public void checkType(String asText, Map<String, String> ns) {
    try {
      xpath.compileForStep("function($v as " + asText + ") as " + asText + " {$v}",
          prefixed(ns), null, List.of(), false);
    } catch (XProcException e) {
      throw XProcException.xs(96, "Invalid sequence type: '" + asText + "'");
    }
  }

  /**
   * Converts a value to the given sequence type.
   *
   * @param value the value
   * @param asText the sequence type (null means item()*)
   * @param ns namespaces for resolving QNames in the type and in QName values
   * @param base base URI for making relative xs:anyURI values absolute (may be null)
   */
  public XdmValue convert(XdmValue value, String asText, Map<String, String> ns, URI base) {
    return convert(value, asText, ns, ns, base);
  }

  /**
   * Converts a value to the given sequence type.
   *
   * @param typeNs namespaces for resolving QNames in the sequence type
   * @param ns namespaces for resolving lexical QNames in the value
   */
  public XdmValue convert(XdmValue value, String asText, Map<String, String> typeNs,
      Map<String, String> ns, URI base) {
    if (asText == null) {
      return value;
    }
    String type = asText.trim();
    String itemType = type;
    char last = type.charAt(type.length() - 1);
    if (last == '?' || last == '*' || last == '+') {
      itemType = type.substring(0, type.length() - 1).trim();
    }
    XdmValue v = value;
    if (isXsType(itemType, "QName", typeNs)) {
      v = convertQNames(v, ns);
    } else if (isXsType(itemType, "anyURI", typeNs)) {
      v = convertUris(v, base);
    } else if (itemType.startsWith("map(")) {
      String inner = itemType.substring(4).trim();
      int comma = inner.indexOf(',');
      if (comma > 0 && isXsType(inner.substring(0, comma).trim(), "QName", typeNs)) {
        v = convertMapKeys(v, ns);
      }
    }
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    vars.put(new QName("v"), v);
    try {
      return xpath.evaluateForStep("(function($x as " + type + ") as " + type + " {$x})($v)",
          prefixed(typeNs), null, null, vars);
    } catch (XProcException e) {
      throw XProcException.xd(36, "Value cannot be converted to " + type + ": "
          + e.getMessage());
    }
  }

  private static Map<String, String> prefixed(Map<String, String> ns) {
    Map<String, String> m = new LinkedHashMap<>();
    if (ns != null) {
      for (Map.Entry<String, String> e : ns.entrySet()) {
        if (!e.getKey().isEmpty()) {
          m.put(e.getKey(), e.getValue());
        }
      }
    }
    return m;
  }

  private static boolean isXsType(String itemType, String local, Map<String, String> ns) {
    int colon = itemType.indexOf(':');
    if (itemType.startsWith("Q{")) {
      return itemType.equals("Q{" + Ns.XS + "}" + local);
    }
    if (colon < 0) {
      return false;
    }
    String prefix = itemType.substring(0, colon);
    String uri = ns != null ? ns.get(prefix) : null;
    return Ns.XS.equals(uri) && itemType.substring(colon + 1).equals(local);
  }

  private static XdmValue convertQNames(XdmValue v, Map<String, String> ns) {
    List<XdmItem> items = new ArrayList<>();
    for (XdmItem item : v) {
      if (item instanceof XdmAtomicValue av) {
        AtomicValue u = av.getUnderlyingValue();
        if (u instanceof QNameValue) {
          items.add(item);
        } else if (u instanceof StringValue) {
          items.add(new XdmAtomicValue(QNames.parseEQName(u.getStringValue(), ns, "XD", 61,
              69)));
        } else {
          throw XProcException.xd(68, "Value '" + u.getStringValue()
              + "' cannot be converted to xs:QName");
        }
      } else {
        String s = item.getStringValue();
        items.add(new XdmAtomicValue(QNames.parseEQName(s, ns, "XD", 61, 69)));
      }
    }
    return new XdmValue(items);
  }

  private static XdmValue convertUris(XdmValue v, URI base) {
    List<XdmItem> items = new ArrayList<>();
    for (XdmItem item : v) {
      String s;
      if (item instanceof XdmAtomicValue av) {
        AtomicValue u = av.getUnderlyingValue();
        if (!(u instanceof StringValue)) {
          items.add(item);
          continue;
        }
        s = u.getStringValue();
      } else {
        s = item.getStringValue();
      }
      String resolved = s;
      if (base != null) {
        try {
          URI u = new URI(s.trim());
          if (!u.isAbsolute()) {
            resolved = base.resolve(u).toString();
          }
        } catch (Exception e) {
          // not a valid URI: leave unresolved
        }
      }
      items.add((XdmAtomicValue) XdmValue.wrap(new AnyURIValue(resolved)));
    }
    return new XdmValue(items);
  }

  private static XdmValue convertMapKeys(XdmValue v, Map<String, String> ns) {
    if (v.size() != 1 || !(v.itemAt(0) instanceof XdmMap map)) {
      return v;
    }
    Map<XdmAtomicValue, XdmValue> m = new LinkedHashMap<>();
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      AtomicValue k = e.getKey().getUnderlyingValue();
      if (k instanceof QNameValue) {
        m.put(e.getKey(), e.getValue());
      } else if (k instanceof StringValue) {
        m.put(new XdmAtomicValue(QNames.parseEQName(k.getStringValue(), ns, "XD", 61, 69)),
            e.getValue());
      }
      // entries with other key types are dropped
    }
    return new XdmMap(m);
  }
}
