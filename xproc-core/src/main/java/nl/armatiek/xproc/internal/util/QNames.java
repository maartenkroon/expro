package nl.armatiek.xproc.internal.util;

import java.util.Map;

import net.sf.saxon.om.NameChecker;
import net.sf.saxon.s9api.QName;
import nl.armatiek.xproc.XProcException;

/** Helpers for parsing names. */
public final class QNames {

  private QNames() {
  }

  /**
   * Parses an EQName (local name, prefix:local or Q{uri}local). Unprefixed names are in no
   * namespace.
   *
   * @param errUnbound the XD/XS error number to raise for unbound prefixes
   * @param errSyntax the error number to raise for syntactically invalid names
   * @param kind "XD" or "XS"
   */
  public static QName parseEQName(String value, Map<String, String> ns, String kind,
      int errSyntax, int errUnbound) {
    String s = value == null ? "" : value.trim();
    if (s.startsWith("Q{")) {
      int close = s.indexOf('}');
      if (close < 0) {
        throw error(kind, errSyntax, "Invalid EQName: '" + value + "'");
      }
      String uri = s.substring(2, close);
      String local = s.substring(close + 1);
      if (!NameChecker.isValidNCName(local)) {
        throw error(kind, errSyntax, "Invalid EQName: '" + value + "'");
      }
      return new QName(uri, local);
    }
    int colon = s.indexOf(':');
    if (colon < 0) {
      if (!NameChecker.isValidNCName(s)) {
        throw error(kind, errSyntax, "Invalid QName: '" + value + "'");
      }
      return new QName("", s);
    }
    String prefix = s.substring(0, colon);
    String local = s.substring(colon + 1);
    if (!NameChecker.isValidNCName(prefix) || !NameChecker.isValidNCName(local)) {
      throw error(kind, errSyntax, "Invalid QName: '" + value + "'");
    }
    String uri = ns == null ? null : ns.get(prefix);
    if ("xml".equals(prefix)) {
      uri = "http://www.w3.org/XML/1998/namespace";
    }
    if (uri == null || uri.isEmpty()) {
      throw error(kind, errUnbound, "Namespace prefix '" + prefix + "' is not bound");
    }
    return new QName(prefix, uri, local);
  }

  private static XProcException error(String kind, int number, String msg) {
    return new XProcException(XProcException.errCode(kind, number), msg);
  }

  public static boolean isNCName(String s) {
    return s != null && NameChecker.isValidNCName(s);
  }

  /** A display form of a QName using the conventional p: prefix for the XProc namespace. */
  public static String display(QName name) {
    if (name == null) {
      return "?";
    }
    if (!name.getPrefix().isEmpty()) {
      return name.getPrefix() + ":" + name.getLocalName();
    }
    if (name.getNamespace().isEmpty()) {
      return name.getLocalName();
    }
    return name.getEQName();
  }
}
