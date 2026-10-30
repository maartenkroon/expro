package nl.armatiek.xproc.rest.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.transform.stream.StreamSource;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.OptionSignature;
import nl.armatiek.xproc.PortSignature;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.XProcResult;
import nl.armatiek.xproc.rest.model.Document;
import nl.armatiek.xproc.rest.model.Message;
import nl.armatiek.xproc.rest.model.OptionDescription;
import nl.armatiek.xproc.rest.model.PipelineDescription;
import nl.armatiek.xproc.rest.model.PortDescription;
import nl.armatiek.xproc.rest.model.RunResult;
import nl.armatiek.xproc.rest.service.PipelineRegistry.StoredPipeline;

/** Compiles and runs pipelines on behalf of the controllers. */
@Service
public class XProcService {

  /** The parts of a run request, shared by stored and ad-hoc runs. */
  public record RunSpec(Map<String, List<Document>> inputs, Map<String, String> options,
      Map<String, String> xpathOptions, Map<String, String> namespaces, List<String> outputs) {
  }

  private final XProcProcessor processor;
  private final DocumentConverter documents;

  public XProcService(XProcProcessor processor, DocumentConverter documents) {
    this.processor = processor;
    this.documents = documents;
  }

  /**
   * Compiles a pipeline document. Without a base URI, the URL of the current request is used
   * as base URI.
   */
  public StoredPipeline compile(String id, byte[] source, URI baseUri,
      Map<String, String> staticOptions, Map<String, String> namespaces) {
    Map<QName, XdmValue> statics = new LinkedHashMap<>();
    staticOptions.forEach((name, value) ->
        statics.put(Names.parse(name, namespaces), DocumentConverter.untyped(value)));
    StreamSource src = new StreamSource(new ByteArrayInputStream(source));
    // Without a base URI the pipeline is identified by the URL of the request
    src.setSystemId(baseUri != null ? baseUri.toString()
        : ServletUriComponentsBuilder.fromCurrentRequestUri().replaceQuery(null).toUriString());
    XProcPipeline pipeline = processor.compile(src, statics);
    return new StoredPipeline(id, pipeline, source, baseUri, OffsetDateTime.now());
  }

  /** Runs a pipeline with the documents and options of a run request. */
  public RunResult run(XProcPipeline pipeline, RunSpec spec) {
    Map<String, String> ns = spec.namespaces() == null ? Map.of() : spec.namespaces();
    Map<String, List<XProcDocument>> inputs = new LinkedHashMap<>();
    if (spec.inputs() != null) {
      for (Map.Entry<String, List<Document>> e : spec.inputs().entrySet()) {
        requirePort(pipeline.getInputs(), e.getKey(), "input");
        List<XProcDocument> docs = new ArrayList<>();
        for (Document d : e.getValue()) {
          docs.add(documents.toXProc(d, ns));
        }
        inputs.put(e.getKey(), docs);
      }
    }
    Map<QName, XdmValue> options = new LinkedHashMap<>();
    if (spec.options() != null) {
      spec.options().forEach((name, value) ->
          options.put(Names.parse(name, ns), DocumentConverter.untyped(value)));
    }
    if (spec.xpathOptions() != null) {
      spec.xpathOptions().forEach((name, expr) ->
          options.put(Names.parse(name, ns), evaluate(expr, ns)));
    }
    List<String> wanted = spec.outputs() == null || spec.outputs().isEmpty() ? null
        : spec.outputs();
    if (wanted != null) {
      wanted.forEach(port -> requirePort(pipeline.getOutputs(), port, "output"));
    }

    List<Message> messages = new ArrayList<>();
    long start = System.nanoTime();
    XProcResult result = RunMessages.collect(messages, () -> pipeline.run(inputs, options));
    long millis = (System.nanoTime() - start) / 1_000_000;

    RunResult rr = new RunResult(new LinkedHashMap<>(), messages, millis)
        .primaryOutput(result.getPrimaryPort());
    for (PortSignature port : pipeline.getOutputs()) {
      if (wanted == null || wanted.contains(port.name())) {
        Map<QName, XdmValue> ser = pipeline.getSerialization(port.name());
        List<Document> docs = new ArrayList<>();
        for (XProcDocument d : result.getDocuments(port.name())) {
          docs.add(documents.toApi(d, ser));
        }
        rr.putOutputsItem(port.name(), docs);
      }
    }
    return rr;
  }

  /** Runs a pipeline on a raw document and returns the single primary output document. */
  public DocumentConverter.Serialized transform(XProcPipeline pipeline, byte[] body,
      String contentType, URI baseUri, Map<String, String> options,
      Map<String, String> namespaces) {
    Map<String, List<XProcDocument>> inputs = new LinkedHashMap<>();
    if (body != null) {
      String primary = pipeline.getPrimaryInput();
      if (primary == null) {
        throw new BadRequestException("The pipeline has no primary input port");
      }
      inputs.put(primary, List.of(documents.parse(body,
          DocumentConverter.mediaType(contentType), baseUri)));
    }
    String output = pipeline.getPrimaryOutput();
    if (output == null) {
      throw new BadRequestException("The pipeline has no primary output port");
    }
    Map<QName, XdmValue> opts = new LinkedHashMap<>();
    options.forEach((name, value) ->
        opts.put(Names.parse(name, namespaces), DocumentConverter.untyped(value)));
    List<XProcDocument> docs = RunMessages.collect(new ArrayList<>(),
        () -> pipeline.run(inputs, opts)).getDocuments(output);
    if (docs.size() > 1) {
      throw new OutputTooLargeException("The primary output port '" + output + "' produced "
          + docs.size() + " documents");
    }
    return docs.isEmpty() ? null : documents.serialize(docs.get(0),
        pipeline.getSerialization(output));
  }

  /** Describes a stored pipeline. */
  public PipelineDescription describe(StoredPipeline sp) {
    XProcPipeline p = sp.pipeline();
    PipelineDescription d = new PipelineDescription(sp.id(), ports(p.getInputs()),
        ports(p.getOutputs()), new ArrayList<>())
        .type(p.getType() == null ? null : p.getType().getEQName())
        .baseUri(sp.baseUri())
        .compiledAt(sp.compiledAt());
    for (OptionSignature o : p.getOptions()) {
      d.addOptionsItem(new OptionDescription(Names.format(o.name()), o.required(), o.isStatic())
          .type(o.type())
          .defaultExpression(o.defaultExpression()));
    }
    return d;
  }

  private static List<PortDescription> ports(List<PortSignature> ports) {
    List<PortDescription> list = new ArrayList<>();
    for (PortSignature p : ports) {
      list.add(new PortDescription(p.name(), p.primary(), p.sequence())
          .contentTypes(p.contentTypes())
          .hasDefault(p.hasDefault()));
    }
    return list;
  }

  private static void requirePort(List<PortSignature> ports, String name, String kind) {
    if (ports.stream().noneMatch(p -> p.name().equals(name))) {
      throw new BadRequestException("The pipeline has no " + kind + " port '" + name + "'");
    }
  }

  private XdmValue evaluate(String expr, Map<String, String> namespaces) {
    XPathCompiler xpc = processor.getSaxonProcessor().newXPathCompiler();
    namespaces.forEach(xpc::declareNamespace);
    try {
      return xpc.evaluate(expr, null);
    } catch (SaxonApiException e) {
      throw new BadRequestException("Invalid XPath expression '" + expr + "': "
          + e.getMessage(), e);
    }
  }
}
