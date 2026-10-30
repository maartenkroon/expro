package nl.armatiek.xproc.rest.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;

/** Conversion between QNames and their representation in the API. */
public final class Names {

  private Names() {
  }

  /**
   * Parses a name given as an EQName ({@code Q{uri}local}), a prefixed name whose prefix is
   * bound in {@code namespaces}, or an unprefixed name (in no namespace).
   */
  public static QName parse(String name, Map<String, String> namespaces) {
    String n = name == null ? "" : name.strip();
    try {
      if (n.startsWith("Q{")) {
        int end = n.indexOf('}');
        if (end < 0) {
          throw new BadRequestException("Invalid EQName: " + name);
        }
        return check(new QName(n.substring(2, end), n.substring(end + 1)), name);
      }
      int colon = n.indexOf(':');
      if (colon < 0) {
        return check(new QName(n), name);
      }
      String prefix = n.substring(0, colon);
      String uri = namespaces == null ? null : namespaces.get(prefix);
      if (uri == null) {
        throw new BadRequestException("Unbound namespace prefix in name: " + name);
      }
      return check(new QName(prefix, uri, n.substring(colon + 1)), name);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Invalid name: " + name, e);
    }
  }

  private static QName check(QName qn, String original) {
    if (!net.sf.saxon.om.NameChecker.isValidNCName(qn.getLocalName())) {
      throw new BadRequestException("Invalid name: " + original);
    }
    return qn;
  }

  /** Formats a name as an EQName, or as its local name when it is in no namespace. */
  public static String format(QName name) {
    if (name == null) {
      return null;
    }
    String uri = name.getNamespaceUri().toString();
    return uri.isEmpty() ? name.getLocalName() : name.getEQName();
  }

  /** Parses {@code NAME=VALUE} pairs from query parameters. */
  public static Map<String, String> pairs(List<String> values, String what) {
    Map<String, String> map = new LinkedHashMap<>();
    if (values != null) {
      for (String v : values) {
        int eq = v.indexOf('=');
        if (eq <= 0) {
          throw new BadRequestException("Invalid " + what + " parameter (expected NAME=VALUE): "
              + v);
        }
        map.put(v.substring(0, eq), v.substring(eq + 1));
      }
    }
    return map;
  }
}
