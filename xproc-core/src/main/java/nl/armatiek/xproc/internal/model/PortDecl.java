package nl.armatiek.xproc.internal.model;

import java.util.List;

import nl.armatiek.xproc.MediaType;

/** The declaration of an input or output port. */
public final class PortDecl {

  public final String name;
  public final boolean input;
  public boolean sequence;
  public boolean primary;
  /** True if primary was specified explicitly (true or false). */
  public Boolean primarySpecified;
  public List<MediaType> contentTypes;
  public String contentTypesText;
  /** Default connection (p:input) or the connection of a compound step output. */
  public Connection connection;
  /** Serialization parameters expression (p:output of a pipeline). */
  public XPathExpr serialization;
  public Location location = Location.UNKNOWN;

  public PortDecl(String name, boolean input) {
    this.name = name;
    this.input = input;
    this.contentTypes = MediaType.parseContentTypes(null);
  }

  public PortDecl copy(String newName) {
    PortDecl p = new PortDecl(newName, input);
    p.sequence = sequence;
    p.primary = primary;
    p.primarySpecified = primarySpecified;
    p.contentTypes = contentTypes;
    p.contentTypesText = contentTypesText;
    p.location = location;
    return p;
  }

  @Override
  public String toString() {
    return (input ? "input " : "output ") + name;
  }
}
