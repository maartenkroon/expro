package nl.armatiek.xproc.internal.model;

import java.util.LinkedHashSet;
import java.util.Set;

import net.sf.saxon.s9api.QName;

/**
 * What a p:run invocation declares about the pipeline it runs. Each p:run element gets its own
 * {@link StepDeclaration}: an input port per p:run-input (plus the anonymous pipeline port), an
 * output port per p:output and an option per p:run-option.
 */
public final class RunInfo {

  /** The name of the anonymous input port that receives the pipeline. */
  public static final String PIPELINE_PORT = "!pipeline";

  /** The primary input port named by p:run-input, or null. */
  public String primaryInput;
  /** The primary output port declared with p:output, or null. */
  public String primaryOutput;
  /** The p:run-input ports. */
  public final Set<String> inputs = new LinkedHashSet<>();
  /** The p:run-option options with static="true". */
  public final Set<QName> staticOptions = new LinkedHashSet<>();
}
