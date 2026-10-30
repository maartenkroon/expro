package nl.armatiek.xproc.internal.util;

import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.value.BooleanValue;
import net.sf.saxon.value.NumericValue;

/** Small helpers for working with XDM values. */
public final class Xdm {

  private Xdm() {
  }

  /** The boolean value of the first item (which must be an xs:boolean). */
  public static boolean bool(XdmValue v) {
    if (v == null || v.size() == 0) {
      return false;
    }
    XdmItem item = v.itemAt(0);
    if (item instanceof XdmAtomicValue av && av.getUnderlyingValue() instanceof BooleanValue b) {
      return b.getBooleanValue();
    }
    String s = item.getStringValue().trim();
    return "true".equals(s) || "1".equals(s);
  }

  /** The string value of the first item, or null for the empty sequence. */
  public static String string(XdmValue v) {
    if (v == null || v.size() == 0) {
      return null;
    }
    return v.itemAt(0).getStringValue();
  }

  /** The integer value of the first item, or the default for the empty sequence. */
  public static long integer(XdmValue v, long dflt) {
    if (v == null || v.size() == 0) {
      return dflt;
    }
    XdmItem item = v.itemAt(0);
    if (item instanceof XdmAtomicValue av && av.getUnderlyingValue() instanceof NumericValue n) {
      try {
        return n.longValue();
      } catch (net.sf.saxon.trans.XPathException e) {
        throw new NumberFormatException(e.getMessage());
      }
    }
    return Long.parseLong(item.getStringValue().trim());
  }

  public static boolean isEmpty(XdmValue v) {
    return v == null || v.size() == 0;
  }
}
