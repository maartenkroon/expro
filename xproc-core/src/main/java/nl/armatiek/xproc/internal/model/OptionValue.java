package nl.armatiek.xproc.internal.model;

import net.sf.saxon.s9api.SequenceType;

/** The value specified for an option on a step invocation (p:with-option or shortcut). */
public final class OptionValue {

  /** Select expression (p:with-option, or a map attribute shortcut). */
  public XPathExpr select;
  /** Attribute value template (non-map shortcut). */
  public ValueTemplate avt;
  public String asText;
  public SequenceType as;
  public boolean collection;
  /** Connection providing the context item (explicit or default readable port). */
  public Connection connection;
  public ExprContext context;
  public Location location = Location.UNKNOWN;
}
