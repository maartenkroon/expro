package nl.armatiek.xproc.internal.model;

import java.util.LinkedHashMap;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;

/** A p:declare-step: the signature of a step and, for pipelines, its subpipeline. */
public final class StepDeclaration {

  public final XdmNode element;
  public final Location location;
  public QName type;
  public String name;
  public final Map<String, PortDecl> inputs = new LinkedHashMap<>();
  public final Map<String, PortDecl> outputs = new LinkedHashMap<>();
  public final Map<QName, OptionDecl> options = new LinkedHashMap<>();
  public boolean psviRequired;
  public boolean privateVisibility;
  /** The subpipeline (null for external steps). */
  public Container body;
  /** True while the body is being parsed (used for p:step-available). */
  public boolean underConstruction;
  /** Set when the body has been parsed. */
  public boolean bodyParsed;
  /** True for external steps (declarations without a subpipeline). */
  public boolean external;
  /** For the declaration of a p:run invocation: what it declares about the run pipeline. */
  public RunInfo run;

  public StepDeclaration(XdmNode element) {
    this.element = element;
    this.location = Location.of(element);
  }

  public boolean isExternal() {
    return external;
  }

  public PortDecl primaryInput() {
    for (PortDecl p : inputs.values()) {
      if (p.primary) {
        return p;
      }
    }
    return null;
  }

  public PortDecl primaryOutput() {
    for (PortDecl p : outputs.values()) {
      if (p.primary) {
        return p;
      }
    }
    return null;
  }

  @Override
  public String toString() {
    return "declare-step " + (type == null ? name : type.getClarkName());
  }
}
