package nl.armatiek.xproc.internal.model;

import java.util.ArrayList;
import java.util.List;

/** The connection of an input (or output, variable, option context): bindings plus a select. */
public final class Connection {

  public final List<Binding> bindings = new ArrayList<>();
  /** Optional select expression applied to every document. */
  public XPathExpr select;
  /** Location of the element that established the connection. */
  public Location location = Location.UNKNOWN;

  public Connection() {
  }

  public Connection(Binding b) {
    bindings.add(b);
  }

  public boolean isEmpty() {
    return bindings.isEmpty();
  }
}
