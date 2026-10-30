package nl.armatiek.xproc.internal.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;

/** A step in a subpipeline (atomic or compound). */
public abstract class StepNode extends Node {

  public String name;
  public boolean explicitName;
  public QName type;
  public ValueTemplate message;
  public final List<String> dependsNames = new ArrayList<>();
  /** Input connections, by port name. */
  public final Map<String, Connection> inputs = new LinkedHashMap<>();
  /** Output ports as visible from outside the step, by port name. */
  public final Map<String, PortDecl> outputs = new LinkedHashMap<>();
  /** The default readable port in the environment of this step (may be null). */
  public Binding.Pipe drp;

  protected StepNode(XdmNode element) {
    super(element);
  }

  public String primaryOutput() {
    for (PortDecl p : outputs.values()) {
      if (p.primary) {
        return p.name;
      }
    }
    return null;
  }

  @Override
  public String displayName() {
    return name;
  }
}
