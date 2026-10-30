package nl.armatiek.xproc.internal.model;

import java.util.LinkedHashMap;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;

/** An invocation of an atomic step (standard step, external step or declared pipeline). */
public final class AtomicStep extends StepNode {

  public StepDeclaration decl;
  public final Map<QName, OptionValue> options = new LinkedHashMap<>();
  /** Values of options that are static on the declaration are taken from the declaration. */

  public AtomicStep(XdmNode element) {
    super(element);
  }
}
