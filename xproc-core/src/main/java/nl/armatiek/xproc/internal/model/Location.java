package nl.armatiek.xproc.internal.model;

import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.XProcException;

/** A location in a pipeline document. */
public record Location(String uri, int line, int column) {

  public static final Location UNKNOWN = new Location(null, -1, -1);

  public static Location of(XdmNode node) {
    if (node == null) {
      return UNKNOWN;
    }
    String uri = node.getUnderlyingNode().getSystemId();
    return new Location(uri, node.getLineNumber(), node.getColumnNumber());
  }

  public XProcException apply(XProcException e) {
    return e.at(uri, line, column);
  }

  @Override
  public String toString() {
    return (uri == null ? "?" : uri) + (line > 0 ? ":" + line : "");
  }
}
