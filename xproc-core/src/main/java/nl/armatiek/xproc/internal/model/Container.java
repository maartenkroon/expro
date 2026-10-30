package nl.armatiek.xproc.internal.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;

/**
 * A compound step or a branch of a compound step: anything that contains a subpipeline.
 */
public class Container extends StepNode {

  public enum Kind {
    PIPELINE, FOR_EACH, VIEWPORT, CHOOSE, WHEN, OTHERWISE, IF, GROUP, TRY, CATCH, FINALLY
  }

  public final Kind kind;
  /** The subpipeline (steps and variables), in document order. */
  public final List<Node> children = new ArrayList<>();
  /** The subpipeline in evaluation order. */
  public List<Node> order = new ArrayList<>();
  /** Ports readable from inside the container ("current", "error", pipeline inputs). */
  public final Map<String, PortDecl> innerInputs = new LinkedHashMap<>();
  /**
   * Source (for-each, viewport) or context (choose, when, if) connection. Null means: use the
   * default readable port (for choose/when/if: the context is undefined if there is none).
   */
  public Connection source;
  /** Test expression (when, if) or match pattern (viewport). */
  public XPathExpr test;
  public boolean collection;
  /** Branches: p:when/p:otherwise for choose; p:catch (and p:finally) for try. */
  public final List<Container> branches = new ArrayList<>();
  public Container finallyBranch;
  /** Error codes of a p:catch (empty means: catch all). */
  public final List<QName> codes = new ArrayList<>();
  /** The declaration (for PIPELINE). */
  public StepDeclaration declaration;
  /** True if the primary output port was added implicitly. */
  public boolean implicitOutput;

  public Container(XdmNode element, Kind kind) {
    super(element);
    this.kind = kind;
  }

  public boolean isBranch() {
    return kind == Kind.WHEN || kind == Kind.OTHERWISE || kind == Kind.CATCH
        || kind == Kind.FINALLY;
  }

  /** The last step in the subpipeline (in document order), or null. */
  public StepNode lastStep() {
    for (int i = children.size() - 1; i >= 0; i--) {
      if (children.get(i) instanceof StepNode s) {
        return s;
      }
    }
    return null;
  }
}
