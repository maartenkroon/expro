package nl.armatiek.xproc.internal.model;

import java.util.LinkedHashSet;
import java.util.Set;

import net.sf.saxon.s9api.XdmNode;

/** Something that occurs in a subpipeline: a step or a variable. */
public abstract class Node {

  public final XdmNode element;
  public final Location location;
  public Container parent;
  /** Position among the children of the parent. */
  public int index;
  /** Producers (nodes) and binders referenced from within this node (including descendants). */
  public final Set<Object> refs = new LinkedHashSet<>();
  /** Sibling nodes that must be evaluated before this node. */
  public final Set<Node> deps = new LinkedHashSet<>();

  protected Node(XdmNode element) {
    this.element = element;
    this.location = Location.of(element);
  }

  public abstract String displayName();
}
