package nl.armatiek.xproc.internal.model;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmNode;

/** A p:variable. */
public final class VariableNode extends Node implements Binder {

  public final QName name;
  public String asText;
  public SequenceType as;
  public XPathExpr select;
  public boolean collection;
  /** The connection providing the context (explicit, or the default readable port). */
  public Connection connection;
  public boolean explicitConnection;

  public VariableNode(XdmNode element, QName name) {
    super(element);
    this.name = name;
  }

  @Override
  public QName getName() {
    return name;
  }

  @Override
  public String displayName() {
    return "$" + name.getClarkName();
  }
}
