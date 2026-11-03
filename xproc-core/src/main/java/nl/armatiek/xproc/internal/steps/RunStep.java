package nl.armatiek.xproc.internal.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.model.OptionDecl;
import nl.armatiek.xproc.internal.model.PortDecl;
import nl.armatiek.xproc.internal.model.RunInfo;
import nl.armatiek.xproc.internal.model.StepDeclaration;
import nl.armatiek.xproc.internal.parser.PipelineParser;
import nl.armatiek.xproc.internal.runtime.Executor;

/** p:run: compiles the pipeline on its anonymous input port and runs it. */
final class RunStep implements StepImplementation {

  @Override
  public void run(StepContext ctx) {
    StepDeclaration self = ctx.declaration();
    RunInfo info = self.run;
    XProcDocument pipelineDoc = ctx.input(RunInfo.PIPELINE_PORT);
    XdmNode node = pipelineDoc == null ? null : pipelineDoc.getNode();
    if (node == null) {
      throw XProcException.xc(200, "The input of p:run is not a pipeline");
    }

    Map<QName, XdmValue> statics = new LinkedHashMap<>();
    for (QName name : info.staticOptions) {
      statics.put(name, ctx.option(name));
    }
    StepDeclaration pipeline;
    try {
      pipeline = new PipelineParser(ctx.pc(), statics).parse(node);
    } catch (XProcException e) {
      throw XProcException.xc(200, "The input of p:run is not a valid pipeline: "
          + e.getMessage(), e);
    }

    // When p:run names ports, its primary ports must be those of the pipeline
    PortDecl primaryIn = pipeline.primaryInput();
    String pipelinePrimaryIn = primaryIn == null ? null : primaryIn.name;
    if (!info.inputs.isEmpty() && !Objects.equals(info.primaryInput, pipelinePrimaryIn)) {
      throw XProcException.xc(206, "The primary input port of the pipeline is "
          + describe(pipelinePrimaryIn) + ", but p:run specifies " + describe(info.primaryInput));
    }
    PortDecl primaryOut = pipeline.primaryOutput();
    String pipelinePrimaryOut = primaryOut == null ? null : primaryOut.name;
    if (!self.outputs.isEmpty() && !Objects.equals(info.primaryOutput, pipelinePrimaryOut)) {
      throw XProcException.xc(207, "The primary output port of the pipeline is "
          + describe(pipelinePrimaryOut) + ", but p:run specifies "
          + describe(info.primaryOutput));
    }

    // Ports without a p:run-input are empty, and p:run-inputs for other ports are ignored. A
    // p:run-input that provides no documents leaves the default connection of the port in place.
    Map<String, List<XProcDocument>> inputs = new LinkedHashMap<>();
    for (PortDecl port : pipeline.inputs.values()) {
      List<XProcDocument> docs = info.inputs.contains(port.name) ? ctx.inputs(port.name)
          : List.of();
      if (!docs.isEmpty() || port.connection == null) {
        inputs.put(port.name, new ArrayList<>(docs));
      } else if (!info.inputs.contains(port.name)) {
        inputs.put(port.name, List.of());
      }
    }
    // Options the pipeline does not declare are ignored
    Map<QName, XdmValue> options = new LinkedHashMap<>();
    for (QName name : self.options.keySet()) {
      OptionDecl od = pipeline.options.get(name);
      if (od != null && !od.isStatic && !info.staticOptions.contains(name)) {
        options.put(name, ctx.option(name));
      }
    }

    Map<String, List<XProcDocument>> outputs = new Executor(ctx.pc()).runTopLevel(pipeline,
        inputs, options);
    for (PortDecl port : self.outputs.values()) {
      List<XProcDocument> docs = outputs.get(port.name);
      if (docs != null) {
        for (XProcDocument d : docs) {
          ctx.output(port.name, d);
        }
      }
    }
  }

  private static String describe(String port) {
    return port == null ? "none" : "'" + port + "'";
  }
}
