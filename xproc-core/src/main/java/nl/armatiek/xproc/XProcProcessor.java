package nl.armatiek.xproc;

import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.xml.transform.Source;

import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.parser.PipelineParser;

/**
 * Entry point of the XProc 3.1 processor: compiles pipelines and provides document utilities.
 * <p>
 * Example:
 *
 * <pre>
 * XProcProcessor processor = new XProcProcessor();
 * XProcPipeline pipeline = processor.compile(new File("pipeline.xpl"));
 * XProcResult result = pipeline.newInvocation()
 *     .withInput("source", processor.loadDocument(URI.create("file:/tmp/in.xml"), null))
 *     .withOption("param", "value")
 *     .run();
 * for (XProcDocument doc : result.getPrimaryDocuments()) {
 *   processor.serialize(doc, System.out, pipeline.getSerialization(result.getPrimaryPort()));
 * }
 * </pre>
 *
 * An XProcProcessor is thread-safe.
 */
public final class XProcProcessor {

  private final ProcessorContext pc;

  public XProcProcessor() {
    this(new XProcConfiguration());
  }

  public XProcProcessor(XProcConfiguration config) {
    this.pc = new ProcessorContext(config.copy());
    PipelineParser.loadStandardLibrary(pc);
  }

  /** The underlying Saxon processor. */
  public Processor getSaxonProcessor() {
    return pc.processor;
  }

  public String getProductName() {
    return ProcessorContext.PRODUCT_NAME;
  }

  public String getProductVersion() {
    return ProcessorContext.PRODUCT_VERSION;
  }

  /** Compiles the pipeline at the given URI. */
  public XProcPipeline compile(URI uri) {
    return compile(uri, Map.of());
  }

  /**
   * Compiles the pipeline at the given URI.
   *
   * @param staticOptions values for static options (override the configuration)
   */
  public XProcPipeline compile(URI uri, Map<QName, XdmValue> staticOptions) {
    var res = pc.loader.read(uri);
    XdmNode doc = pc.loader.parseXml(res.bytes(), res.uri(), null, null);
    return compile(doc, staticOptions);
  }

  public XProcPipeline compile(File file) {
    return compile(file.getAbsoluteFile().toURI(), Map.of());
  }

  public XProcPipeline compile(File file, Map<QName, XdmValue> staticOptions) {
    return compile(file.getAbsoluteFile().toURI(), staticOptions);
  }

  /** Compiles a pipeline from a JAXP source. */
  public XProcPipeline compile(Source source, Map<QName, XdmValue> staticOptions) {
    DocumentBuilder db = pc.processor.newDocumentBuilder();
    db.setLineNumbering(true);
    try {
      return compile(db.build(source), staticOptions);
    } catch (SaxonApiException e) {
      throw XProcException.xs(100, "Cannot parse pipeline: " + e.getMessage())
          .at(e.getSystemId(), e.getLineNumber(), -1);
    }
  }

  /** Compiles a pipeline from a string (the base URI is used to resolve relative URIs). */
  public XProcPipeline compile(String pipelineXml, URI baseUri,
      Map<QName, XdmValue> staticOptions) {
    XdmNode doc = pc.loader.parseXml(pipelineXml.getBytes(StandardCharsets.UTF_8), baseUri,
        null, null);
    return compile(doc, staticOptions);
  }

  /** Compiles a pipeline from a document node (or p:declare-step element). */
  public XProcPipeline compile(XdmNode pipeline, Map<QName, XdmValue> staticOptions) {
    PipelineParser parser = new PipelineParser(pc, staticOptions);
    return new XProcPipeline(pc, parser.parse(pipeline));
  }

  /**
   * Loads a document like p:load does.
   *
   * @param contentType the content type, or null to determine it from the resource
   */
  public XProcDocument loadDocument(URI uri, String contentType) {
    return pc.loader.load(uri, contentType, null, null);
  }

  public XProcDocument loadDocument(File file, String contentType) {
    return loadDocument(file.getAbsoluteFile().toURI(), contentType);
  }

  /** Parses a document from bytes according to the content type. */
  public XProcDocument parseDocument(byte[] content, MediaType contentType, URI baseUri) {
    return pc.loader.parse(content, contentType, contentType.getCharset(StandardCharsets.UTF_8),
        baseUri, null, Map.of(), false);
  }

  /** Wraps an existing XDM value (a document node, or a JSON value) as a document. */
  public XProcDocument newDocument(XdmValue value, MediaType contentType, URI baseUri) {
    return XProcDocument.of(pc.processor, value, contentType, baseUri);
  }

  /**
   * Serializes a document. The serialization property of the document takes precedence over
   * the given parameters.
   */
  public void serialize(XProcDocument doc, OutputStream out, Map<QName, XdmValue> params) {
    pc.serializer.serialize(doc, out, params);
  }

  /** Serializes a document to a string. */
  public String serializeToString(XProcDocument doc, Map<QName, XdmValue> params) {
    return pc.serializer.serializeToString(doc, params);
  }
}
