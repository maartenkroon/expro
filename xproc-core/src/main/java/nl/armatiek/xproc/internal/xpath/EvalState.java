package nl.armatiek.xproc.internal.xpath;

import java.util.List;

import net.sf.saxon.s9api.XdmItem;
import nl.armatiek.xproc.internal.model.ExprContext;

/**
 * Thread-local state for the evaluation of expressions by the XProc processor (as opposed to
 * expressions evaluated by steps). The XProc extension functions are only available while such
 * a state is active.
 */
public final class EvalState {

  private static final ThreadLocal<EvalState> CURRENT = new ThreadLocal<>();
  /** The default collection for expressions evaluated by steps (XSLT, XQuery). */
  public static final ThreadLocal<List<XdmItem>> STEP_COLLECTION = new ThreadLocal<>();

  public final ExprContext context;
  public final int iterationPosition;
  public final int iterationSize;
  /** The default collection (null if none). */
  public final List<XdmItem> collection;
  public final boolean staticAnalysis;

  public EvalState(ExprContext context, int iterationPosition, int iterationSize,
      List<XdmItem> collection, boolean staticAnalysis) {
    this.context = context;
    this.iterationPosition = iterationPosition;
    this.iterationSize = iterationSize;
    this.collection = collection;
    this.staticAnalysis = staticAnalysis;
  }

  public static EvalState current() {
    return CURRENT.get();
  }

  /** Activates a state; returns the previous state which must be restored with {@link #restore}. */
  public static EvalState push(EvalState state) {
    EvalState prev = CURRENT.get();
    CURRENT.set(state);
    return prev;
  }

  public static void restore(EvalState prev) {
    if (prev == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(prev);
    }
  }
}
