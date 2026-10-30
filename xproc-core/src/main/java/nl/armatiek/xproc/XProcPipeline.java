package nl.armatiek.xproc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.model.OptionDecl;
import nl.armatiek.xproc.internal.model.PortDecl;
import nl.armatiek.xproc.internal.model.StepDeclaration;
import nl.armatiek.xproc.internal.runtime.Executor;
import nl.armatiek.xproc.internal.xpath.XPathEngine.ContextState;

/**
 * A compiled pipeline. A pipeline is immutable and can be run any number of times, also
 * concurrently from several threads.
 */
public final class XProcPipeline {

  private final ProcessorContext pc;
  private final StepDeclaration decl;

  XProcPipeline(ProcessorContext pc, StepDeclaration decl) {
    this.pc = pc;
    this.decl = decl;
  }

  /** The step type of the pipeline (may be null). */
  public QName getType() {
    return decl.type;
  }

  public List<PortSignature> getInputs() {
    List<PortSignature> list = new ArrayList<>();
    for (PortDecl p : decl.inputs.values()) {
      list.add(new PortSignature(p.name, p.primary, p.sequence, p.contentTypesText,
          p.connection != null));
    }
    return list;
  }

  public List<PortSignature> getOutputs() {
    List<PortSignature> list = new ArrayList<>();
    for (PortDecl p : decl.outputs.values()) {
      list.add(new PortSignature(p.name, p.primary, p.sequence, p.contentTypesText, false));
    }
    return list;
  }

  public List<OptionSignature> getOptions() {
    List<OptionSignature> list = new ArrayList<>();
    for (OptionDecl o : decl.options.values()) {
      list.add(new OptionSignature(o.name, o.asText, o.required, o.isStatic,
          o.select == null ? null : o.select.text));
    }
    return list;
  }

  /** The name of the primary input port, or null. */
  public String getPrimaryInput() {
    PortDecl p = decl.primaryInput();
    return p == null ? null : p.name;
  }

  /** The name of the primary output port, or null. */
  public String getPrimaryOutput() {
    PortDecl p = decl.primaryOutput();
    return p == null ? null : p.name;
  }

  /**
   * The serialization parameters specified on an output port (the serialization attribute of
   * p:output). Empty if none were specified.
   */
  public Map<QName, XdmValue> getSerialization(String port) {
    PortDecl p = decl.outputs.get(port);
    if (p == null || p.serialization == null) {
      return Map.of();
    }
    XdmValue v = pc.xpath.evaluate(p.serialization, null, ContextState.UNDEFINED,
        staticVariables(p), null, null, true);
    return DocumentSerializer.toParams(v);
  }

  private static Map<QName, XdmValue> staticVariables(PortDecl p) {
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    for (var e : p.serialization.context.bindings.entrySet()) {
      if (e.getValue().isStatic()) {
        vars.put(e.getKey(), e.getValue().getStaticValue());
      }
    }
    return vars;
  }

  /** Starts building an invocation of this pipeline. */
  public Invocation newInvocation() {
    return new Invocation();
  }

  /**
   * Runs the pipeline.
   *
   * @param inputs documents for the input ports; ports that are not given use their default
   *        connection, or receive an empty sequence
   * @param options option values; values are converted to the declared types
   */
  public XProcResult run(Map<String, List<XProcDocument>> inputs, Map<QName, XdmValue> options) {
    Executor ex = new Executor(pc);
    Map<String, List<XProcDocument>> out = ex.runTopLevel(decl, inputs == null ? Map.of()
        : inputs, options == null ? Map.of() : options);
    return new XProcResult(out, getPrimaryOutput());
  }

  /** A builder for a pipeline run. */
  public final class Invocation {
    private final Map<String, List<XProcDocument>> inputs = new LinkedHashMap<>();
    private final Map<QName, XdmValue> options = new LinkedHashMap<>();

    private Invocation() {
    }

    /** Adds a document to an input port. */
    public Invocation withInput(String port, XProcDocument doc) {
      inputs.computeIfAbsent(port, k -> new ArrayList<>()).add(doc);
      return this;
    }

    /** Connects an input port to an empty sequence (overrides its default connection). */
    public Invocation withEmptyInput(String port) {
      inputs.computeIfAbsent(port, k -> new ArrayList<>());
      return this;
    }

    public Invocation withOption(QName name, XdmValue value) {
      options.put(name, value);
      return this;
    }

    /** Sets an option to a string value (as xs:untypedAtomic, so it is cast as needed). */
    public Invocation withOption(String name, String value) {
      options.put(new QName(name), untyped(value));
      return this;
    }

    public XProcResult run() {
      return XProcPipeline.this.run(inputs, options);
    }
  }

  static XdmValue untyped(String value) {
    return XdmValue.wrap(net.sf.saxon.value.StringValue.makeUntypedAtomic(
        net.sf.saxon.str.StringView.of(value)));
  }
}
