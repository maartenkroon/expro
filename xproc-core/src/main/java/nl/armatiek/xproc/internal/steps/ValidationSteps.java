package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.thaiopensource.util.PropertyMapBuilder;
import com.thaiopensource.validate.SchemaReader;
import com.thaiopensource.validate.ValidateProperty;
import com.thaiopensource.validate.ValidationDriver;
import com.thaiopensource.validate.auto.AutoSchemaReader;
import com.thaiopensource.validate.prop.rng.RngProperty;
import com.thaiopensource.validate.rng.CompactSchemaReader;
import com.thaiopensource.validate.rng.SAXSchemaReader;

import net.sf.saxon.s9api.BuildingContentHandler;
import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.Xslt30Transformer;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.steps.Xvrl.Detection;
import nl.armatiek.xproc.internal.util.Xdm;

/**
 * The steps of the XProc 3.1 Validation Steps specification. RELAX NG and NVDL validation use
 * Jing, Schematron uses SchXslt2, XML Schema (1.0) and DTD validation use the JAXP
 * implementation of the JDK, and JSON Schema validation uses the networknt validator.
 */
final class ValidationSteps {

  private static final String SVRL = "http://purl.oclc.org/dsdl/svrl";
  private static final String SCH = "http://purl.oclc.org/dsdl/schematron";
  private static final String RNG = "http://relaxng.org/ns/structure/1.0";
  private static final String NVDL = "http://purl.oclc.org/dsdl/nvdl/ns/structure/1.0";
  private static final QName SERIALIZATION = new QName("serialization");

  /** The compiled SchXslt2 transpiler, per Saxon processor. */
  private static final Map<Processor, XsltExecutable> TRANSPILERS = new WeakHashMap<>();

  private ValidationSteps() {
  }

  static void register(StepRegistry r) {
    r.register("validate-with-relax-ng", () -> ValidationSteps::relaxNg);
    r.register("validate-with-nvdl", () -> ValidationSteps::nvdl);
    r.register("validate-with-schematron", () -> ValidationSteps::schematron);
    r.register("validate-with-xml-schema", () -> ValidationSteps::xmlSchema);
    r.register("validate-with-dtd", () -> ValidationSteps::dtd);
    r.register("validate-with-json-schema", () -> ValidationSteps::jsonSchema);
  }

  // =========================================================================================
  // Common

  /** Checks the report-format option; returns it. */
  private static String reportFormat(StepContext ctx, String... supported) {
    String format = Xdm.string(ctx.option("report-format"));
    for (String s : supported) {
      if (s.equals(format)) {
        return format;
      }
    }
    throw XProcException.xc(117, "Unsupported report format: " + format);
  }

  /**
   * Writes the result and the report, or raises the error with the report as its detail
   * when the document is invalid and assert-valid is true.
   */
  private static void finish(StepContext ctx, XdmNode report, boolean valid, int errorCode,
      XProcDocument result) {
    if (!valid && Xdm.bool(ctx.option("assert-valid"))) {
      throw XProcException.xc(errorCode, "The document is not valid: " + summary(report))
          .addDetail(report);
    }
    ctx.output("result", result);
    ctx.output("report", XProcDocument.of(ctx.processor(), report, MediaType.XML,
        (URI) null));
  }

  private static String summary(XdmNode report) {
    for (XdmNode n : report.select(net.sf.saxon.s9api.streams.Steps.descendant()).toList()) {
      if (n.getNodeKind() == XdmNodeKind.ELEMENT) {
        String local = n.getNodeName().getLocalName();
        if (local.equals("message") || local.equals("text")) {
          return n.getStringValue().strip();
        }
      }
    }
    return "validation failed";
  }

  /** Serializes an XML document (or node) for re-parsing by a validator. */
  private static byte[] xmlBytes(Processor processor, XdmNode node) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Serializer ser = processor.newSerializer(out);
    ser.setOutputProperty(Serializer.Property.METHOD, "xml");
    ser.setOutputProperty(Serializer.Property.ENCODING, "UTF-8");
    ser.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
    try {
      ser.serializeNode(node);
    } catch (SaxonApiException e) {
      throw XProcException.xd(1, "Cannot serialize the document: " + e.getMessage());
    }
    return out.toByteArray();
  }

  private static InputSource inputSource(Processor processor, XProcDocument doc) {
    InputSource is;
    if (doc.getContentType().isText()) {
      is = new InputSource(new StringReader(doc.getNode().getStringValue()));
    } else {
      is = new InputSource(new ByteArrayInputStream(xmlBytes(processor, doc.getNode())));
    }
    if (doc.getBaseURI() != null) {
      is.setSystemId(doc.getBaseURI().toString());
    }
    return is;
  }

  private static String href(XProcDocument doc) {
    return doc.getBaseURI() == null ? null : doc.getBaseURI().toString();
  }

  /** Collects the warnings and errors of a SAX based validator. */
  private static class Collector implements ErrorHandler {
    final Xvrl report;

    Collector(Xvrl report) {
      this.report = report;
    }

    @Override
    public void warning(SAXParseException e) {
      add("warning", e);
    }

    @Override
    public void error(SAXParseException e) {
      add("error", e);
    }

    @Override
    public void fatalError(SAXParseException e) {
      add("fatal-error", e);
    }

    void add(String severity, SAXParseException e) {
      report.add(new Detection(severity, e.getMessage(), e.getSystemId(), e.getLineNumber(),
          e.getColumnNumber(), null, null));
    }
  }

  /**
   * Resolves references to documents on the schema port(s) by base URI and checks the access
   * restrictions of the configuration for other resources.
   */
  static final class Resolver implements EntityResolver, LSResourceResolver {
    private final StepContext ctx;
    private final Map<String, XProcDocument> byUri = new LinkedHashMap<>();
    private final Map<String, XProcDocument> byNamespace = new LinkedHashMap<>();

    Resolver(StepContext ctx, List<XProcDocument> schemas) {
      this.ctx = ctx;
      for (XProcDocument s : schemas) {
        if (s.getBaseURI() != null) {
          byUri.put(s.getBaseURI().toString(), s);
        }
        XdmNode root = root(s);
        if (root != null) {
          String tns = root.getAttributeValue(new QName("targetNamespace"));
          byNamespace.putIfAbsent(tns == null ? "" : tns, s);
        }
      }
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
      if (systemId == null) {
        return null;
      }
      XProcDocument d = byUri.get(systemId);
      if (d != null) {
        return inputSource(ctx.processor(), d);
      }
      check(systemId);
      return null;
    }

    @Override
    public LSInput resolveResource(String type, String namespace, String publicId,
        String systemId, String baseUri) {
      String resolved = systemId;
      if (systemId != null && baseUri != null) {
        try {
          resolved = URI.create(baseUri).resolve(systemId).toString();
        } catch (IllegalArgumentException e) {
          // keep the system identifier as is
        }
      }
      XProcDocument d = resolved == null ? null : byUri.get(resolved);
      if (d == null && namespace != null && systemId == null) {
        d = byNamespace.get(namespace);
      }
      if (d != null) {
        return new Input(publicId, href(d), xmlBytes(ctx.processor(), d.getNode()));
      }
      if (resolved != null) {
        try {
          check(resolved);
        } catch (SAXException e) {
          throw new IllegalStateException(e.getMessage(), e);
        }
      }
      return null;
    }

    private void check(String uri) throws SAXException {
      try {
        ctx.pc().checkAccess(URI.create(uri));
      } catch (IllegalArgumentException e) {
        // not a URI the processor would dereference
      } catch (XProcException e) {
        throw new SAXException(e.getMessage());
      }
    }
  }

  /** An LSInput for a document on a schema port. */
  private record Input(String publicId, String systemId, byte[] bytes) implements LSInput {
    @Override
    public Reader getCharacterStream() {
      return null;
    }

    @Override
    public void setCharacterStream(Reader r) {
    }

    @Override
    public InputStream getByteStream() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void setByteStream(InputStream s) {
    }

    @Override
    public String getStringData() {
      return null;
    }

    @Override
    public void setStringData(String s) {
    }

    @Override
    public String getSystemId() {
      return systemId;
    }

    @Override
    public void setSystemId(String s) {
    }

    @Override
    public String getPublicId() {
      return publicId;
    }

    @Override
    public void setPublicId(String s) {
    }

    @Override
    public String getBaseURI() {
      return systemId;
    }

    @Override
    public void setBaseURI(String s) {
    }

    @Override
    public String getEncoding() {
      return "UTF-8";
    }

    @Override
    public void setEncoding(String s) {
    }

    @Override
    public boolean getCertifiedText() {
      return false;
    }

    @Override
    public void setCertifiedText(boolean b) {
    }
  }

  private static XdmNode root(XProcDocument doc) {
    XdmNode n = doc.getNode();
    if (n == null) {
      return null;
    }
    if (n.getNodeKind() == XdmNodeKind.ELEMENT) {
      return n;
    }
    for (XdmNode c : n.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        return c;
      }
    }
    return null;
  }

  // =========================================================================================
  // RELAX NG and NVDL (Jing)

  private static void relaxNg(StepContext ctx) {
    reportFormat(ctx, "xvrl");
    XProcDocument source = ctx.input("source");
    XProcDocument schema = ctx.input("schema");
    boolean compact = schema.getContentType().isText();
    Xvrl report = new Xvrl("Jing", null).document(source.getBaseURI())
        .schema(schema.getBaseURI(), RNG);
    PropertyMapBuilder props = jingProperties(ctx, report, List.of());
    if (Xdm.bool(ctx.option("dtd-id-idref-warnings"))) {
      RngProperty.CHECK_ID_IDREF.add(props);
    }
    SchemaReader reader = compact ? CompactSchemaReader.getInstance()
        : SAXSchemaReader.getInstance();
    boolean valid = jingValidate(ctx, props, reader, schema, source, report, 153,
        "RELAX NG grammar");
    finish(ctx, report.toDocument(ctx.processor()), valid, 155, source);
  }

  private static void nvdl(StepContext ctx) {
    reportFormat(ctx, "xvrl");
    XProcDocument source = ctx.input("source");
    XProcDocument nvdl = ctx.input("nvdl");
    XdmNode root = root(nvdl);
    if (root == null || !NVDL.equals(root.getNodeName().getNamespace())) {
      throw XProcException.xc(154, "The nvdl document is not an NVDL document");
    }
    Xvrl report = new Xvrl("Jing", null).document(source.getBaseURI())
        .schema(nvdl.getBaseURI(), NVDL);
    PropertyMapBuilder props = jingProperties(ctx, report, ctx.inputs("schemas"));
    boolean valid = jingValidate(ctx, props, new AutoSchemaReader(), nvdl, source, report, 154,
        "NVDL document");
    finish(ctx, report.toDocument(ctx.processor()), valid, 53, source);
  }

  private static PropertyMapBuilder jingProperties(StepContext ctx, Xvrl report,
      List<XProcDocument> schemas) {
    PropertyMapBuilder props = new PropertyMapBuilder();
    props.put(ValidateProperty.ERROR_HANDLER, new Collector(report));
    props.put(ValidateProperty.ENTITY_RESOLVER, new Resolver(ctx, schemas));
    return props;
  }

  private static boolean jingValidate(StepContext ctx, PropertyMapBuilder props,
      SchemaReader reader, XProcDocument schema, XProcDocument source, Xvrl report,
      int schemaError, String what) {
    // Schema errors are collected separately: they are step errors, not validation results
    Xvrl schemaReport = new Xvrl("Jing", null);
    PropertyMapBuilder schemaProps = new PropertyMapBuilder(props.toPropertyMap());
    schemaProps.put(ValidateProperty.ERROR_HANDLER, new Collector(schemaReport));
    ValidationDriver driver = new ValidationDriver(schemaProps.toPropertyMap(),
        props.toPropertyMap(), reader);
    try {
      if (!driver.loadSchema(inputSource(ctx.processor(), schema))) {
        throw XProcException.xc(schemaError, "The schema is not a valid " + what + ": "
            + messages(schemaReport));
      }
    } catch (SAXException | IOException e) {
      throw XProcException.xc(schemaError, "The schema is not a valid " + what + ": "
          + e.getMessage());
    }
    try {
      return driver.validate(inputSource(ctx.processor(), source)) && report.isValid();
    } catch (SAXException | IOException e) {
      report.add(Detection.error(e.getMessage(), href(source), -1, -1));
      return false;
    }
  }

  private static String messages(Xvrl report) {
    StringBuilder sb = new StringBuilder();
    for (Detection d : report.detections()) {
      if (!sb.isEmpty()) {
        sb.append("; ");
      }
      sb.append(d.message());
    }
    return sb.toString();
  }

  // =========================================================================================
  // Schematron (SchXslt2)

  private static void schematron(StepContext ctx) {
    String format = reportFormat(ctx, "svrl", "xvrl");
    XProcDocument source = ctx.input("source");
    XProcDocument schemaDoc = ctx.input("schema");
    XdmNode schemaRoot = root(schemaDoc);
    if (schemaRoot == null || !SCH.equals(schemaRoot.getNodeName().getNamespace())
        || !"schema".equals(schemaRoot.getNodeName().getLocalName())) {
      throw XProcException.xc(151, "The schema is not a Schematron schema");
    }
    Map<QName, XdmValue> parameters = parameters(ctx);
    Map<QName, XdmValue> compile = new LinkedHashMap<>();
    XdmValue c = parameters.remove(Ns.c("compile"));
    if (c != null && c.size() == 1 && c.itemAt(0) instanceof XdmMap m) {
      for (var e : m.entrySet()) {
        compile.put(e.getKey().getQNameValue(), e.getValue());
      }
    }
    parameters.remove(Ns.c("implementation"));
    parameters.remove(Ns.c("xvrl"));
    compile.put(new QName("schxslt", "http://dmaus.name/ns/2023/schxslt", "phase"),
        new XdmAtomicValue(Xdm.string(ctx.option("phase"))));

    Processor processor = ctx.processor();
    // The compiled stylesheet depends on the schema, its base URI and the transpiler parameters
    XsltExecutable validator = ctx.pc().xsltCache.get(processor, "schematron",
        schemaDoc.getNode(), compile, () -> {
          try {
            Xslt30Transformer transpiler = transpiler(processor).load30();
            transpiler.setStylesheetParameters(compile);
            XdmDestination dest = new XdmDestination();
            if (schemaDoc.getBaseURI() != null) {
              dest.setBaseURI(schemaDoc.getBaseURI());
            }
            transpiler.applyTemplates(schemaDoc.getNode(), dest);
            XdmNode stylesheet = dest.getXdmNode();
            return processor.newXsltCompiler().compile(stylesheet.asSource());
          } catch (SaxonApiException e) {
            throw XProcException.xc(151, "Cannot compile the Schematron schema: "
                + e.getMessage());
          }
        });
    XdmNode svrl;
    try {
      Xslt30Transformer t = validator.load30();
      t.setStylesheetParameters(parameters);
      XdmDestination dest = new XdmDestination();
      t.applyTemplates(source.getNode(), dest);
      svrl = dest.getXdmNode();
    } catch (SaxonApiException e) {
      throw XProcException.xc(151, "Schematron validation failed: " + e.getMessage());
    }
    boolean valid = true;
    Xvrl xvrl = new Xvrl("SchXslt2", null).document(source.getBaseURI())
        .schema(schemaDoc.getBaseURI(), SCH);
    for (XdmNode n : svrl.select(net.sf.saxon.s9api.streams.Steps.descendant()).toList()) {
      if (n.getNodeKind() != XdmNodeKind.ELEMENT || !SVRL.equals(n.getNodeName().getNamespace())) {
        continue;
      }
      String local = n.getNodeName().getLocalName();
      if (local.equals("failed-assert") || local.equals("successful-report")) {
        valid = false;
        StringBuilder text = new StringBuilder();
        for (XdmNode t : n.children()) {
          if (t.getNodeKind() == XdmNodeKind.ELEMENT
              && t.getNodeName().getLocalName().equals("text")) {
            text.append(t.getStringValue().strip());
          }
        }
        xvrl.add(new Detection(severity(n.getAttributeValue(new QName("role"))),
            text.toString(), href(source), -1, -1, n.getAttributeValue(new QName("location")),
            n.getAttributeValue(new QName("id"))));
      }
    }
    XdmNode report = format.equals("svrl") ? svrl : xvrl.toDocument(processor);
    finish(ctx, report, valid, 54, source);
  }

  private static synchronized XsltExecutable transpiler(Processor processor)
      throws SaxonApiException {
    XsltExecutable exec = TRANSPILERS.get(processor);
    if (exec == null) {
      var url = ValidationSteps.class.getResource("/content/transpile.xsl");
      if (url == null) {
        throw XProcException.xc(151, "The Schematron implementation is not available");
      }
      exec = processor.newXsltCompiler().compile(new StreamSource(url.toString()));
      TRANSPILERS.put(processor, exec);
    }
    return exec;
  }

  private static String severity(String role) {
    if (role == null) {
      return "error";
    }
    return switch (role.strip().toLowerCase()) {
      case "fatal", "fatal-error" -> "fatal-error";
      case "warning", "warn" -> "warning";
      case "info", "information", "informational" -> "info";
      default -> "error";
    };
  }

  private static Map<QName, XdmValue> parameters(StepContext ctx) {
    Map<QName, XdmValue> m = new LinkedHashMap<>();
    XdmValue v = ctx.option("parameters");
    if (v.size() == 1 && v.itemAt(0) instanceof XdmMap map) {
      for (var e : map.entrySet()) {
        m.put(e.getKey().getQNameValue(), e.getValue());
      }
    }
    return m;
  }

  // =========================================================================================
  // XML Schema (JAXP)

  private static void xmlSchema(StepContext ctx) {
    reportFormat(ctx, "xvrl");
    String version = Xdm.string(ctx.option("version"));
    if (version != null && !version.equals("1.0")) {
      throw XProcException.xc(11, "XML Schema version " + version + " is not available");
    }
    boolean lax = "lax".equals(Xdm.string(ctx.option("mode")));
    boolean hints = Xdm.bool(ctx.option("use-location-hints"));
    XProcDocument source = ctx.input("source");
    List<XProcDocument> schemaDocs = ctx.inputs("schema");
    Resolver resolver = new Resolver(ctx, schemaDocs);

    SchemaFactory factory = SchemaFactory.newDefaultInstance();
    factory.setResourceResolver(resolver);
    Xvrl schemaReport = new Xvrl("JAXP", null);
    factory.setErrorHandler(new Collector(schemaReport) {
      @Override
      public void warning(SAXParseException e) {
        // warnings about the schema are not errors
      }
    });
    Schema schema;
    try {
      List<Source> sources = new ArrayList<>();
      java.util.Set<String> covered = new java.util.HashSet<>();
      for (XProcDocument d : schemaDocs) {
        sources.add(new StreamSource(new ByteArrayInputStream(xmlBytes(ctx.processor(),
            d.getNode())), href(d)));
        XdmNode r = root(d);
        String tns = r == null ? null : r.getAttributeValue(new QName("targetNamespace"));
        covered.add(tns == null ? "" : tns);
      }
      if (hints) {
        sources.addAll(hintedSchemas(ctx, source.getNode(), covered));
      }
      if (Xdm.bool(ctx.option("try-namespaces"))) {
        sources.addAll(namespaceSchemas(ctx, source.getNode(), covered));
      }
      // Without schema documents the schema is empty: a strict validation then fails
      schema = factory.newSchema(sources.toArray(new Source[0]));
    } catch (SAXException | IllegalStateException e) {
      throw XProcException.xc(152, "The schema is not a valid XML Schema: " + e.getMessage());
    }
    if (!schemaReport.isValid()) {
      throw XProcException.xc(152, "The schema is not a valid XML Schema: "
          + messages(schemaReport));
    }

    Xvrl report = new Xvrl("JAXP", null).document(source.getBaseURI())
        .schema(schemaDocs.isEmpty() ? null : schemaDocs.get(0).getBaseURI(),
            XMLConstants.W3C_XML_SCHEMA_NS_URI);
    // The validator handler adds default attribute values to the tree it passes on
    ValidatorHandler validator = schema.newValidatorHandler();
    validator.setResourceResolver(resolver);
    Collector collector = new Collector(report) {
      @Override
      void add(String severity, SAXParseException e) {
        // In lax mode a missing declaration of the document element is not an error
        if (lax && e.getMessage() != null && e.getMessage().startsWith("cvc-elt.1")) {
          return;
        }
        super.add(severity, e);
      }
    };
    validator.setErrorHandler(collector);
    DocumentBuilder db = ctx.processor().newDocumentBuilder();
    if (source.getBaseURI() != null) {
      db.setBaseURI(source.getBaseURI());
    }
    XdmNode validated = null;
    try {
      BuildingContentHandler builder = db.newBuildingContentHandler();
      validator.setContentHandler(builder);
      SAXParserFactory f = SAXParserFactory.newDefaultInstance();
      f.setNamespaceAware(true);
      XMLReader reader = f.newSAXParser().getXMLReader();
      reader.setContentHandler(validator);
      reader.setErrorHandler(collector);
      InputSource is = new InputSource(new ByteArrayInputStream(xmlBytes(ctx.processor(),
          source.getNode())));
      is.setSystemId(href(source));
      reader.parse(is);
      validated = builder.getDocumentNode();
    } catch (SAXException | IOException | SaxonApiException
        | javax.xml.parsers.ParserConfigurationException e) {
      if (report.detections().isEmpty()) {
        report.add(Detection.error(e.getMessage(), href(source), -1, -1));
      }
    }
    boolean valid = report.isValid();
    XProcDocument result = source;
    if (valid && validated != null) {
      result = source.withValue(ctx.processor(), validated, source.getContentType());
    }
    finish(ctx, report.toDocument(ctx.processor()), valid, 156, result);
  }

  private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

  /** The schemas named by xsi:schemaLocation and xsi:noNamespaceSchemaLocation hints. */
  private static List<Source> hintedSchemas(StepContext ctx, XdmNode doc,
      java.util.Set<String> covered) {
    List<Source> list = new ArrayList<>();
    for (XdmNode e : doc.select(net.sf.saxon.s9api.streams.Steps.descendantOrSelf()).toList()) {
      if (e.getNodeKind() != XdmNodeKind.ELEMENT) {
        continue;
      }
      String loc = e.getAttributeValue(new QName(XSI, "schemaLocation"));
      if (loc != null) {
        String[] parts = loc.strip().split("\\s+");
        for (int i = 0; i + 1 < parts.length; i += 2) {
          if (covered.add(parts[i])) {
            addSchema(ctx, list, e.getBaseURI(), parts[i + 1]);
          }
        }
      }
      String noNs = e.getAttributeValue(new QName(XSI, "noNamespaceSchemaLocation"));
      if (noNs != null && covered.add("")) {
        addSchema(ctx, list, e.getBaseURI(), noNs.strip());
      }
    }
    return list;
  }

  /** The schemas found by dereferencing the namespace URIs of the elements of a document. */
  private static List<Source> namespaceSchemas(StepContext ctx, XdmNode doc,
      java.util.Set<String> covered) {
    List<Source> list = new ArrayList<>();
    for (XdmNode e : doc.select(net.sf.saxon.s9api.streams.Steps.descendantOrSelf()).toList()) {
      if (e.getNodeKind() == XdmNodeKind.ELEMENT) {
        String ns = e.getNodeName().getNamespace();
        if (!ns.isEmpty() && covered.add(ns)) {
          addSchema(ctx, list, null, ns);
        }
      }
    }
    return list;
  }

  /** Loads a schema document; resources that cannot be loaded are ignored. */
  private static void addSchema(StepContext ctx, List<Source> list, URI base, String location) {
    try {
      URI uri = base == null ? new URI(location) : base.resolve(location);
      if (!uri.isAbsolute()) {
        return;
      }
      var res = ctx.pc().loader.read(ctx.pc().mapUri(uri));
      list.add(new StreamSource(new ByteArrayInputStream(res.bytes()), res.uri().toString()));
    } catch (java.net.URISyntaxException | IllegalArgumentException | XProcException e) {
      // a hint that cannot be followed
    }
  }

  private static XdmNode parse(Processor processor, byte[] bytes, URI base, boolean dtd) {
    DocumentBuilder db = processor.newDocumentBuilder();
    db.setLineNumbering(true);
    if (base != null) {
      db.setBaseURI(base);
    }
    try {
      return db.build(new StreamSource(new ByteArrayInputStream(bytes),
          base == null ? null : base.toString()));
    } catch (SaxonApiException e) {
      throw XProcException.xd(49, "Cannot parse the validated document: " + e.getMessage());
    }
  }

  // =========================================================================================
  // DTD

  private static void dtd(StepContext ctx) {
    reportFormat(ctx, "xvrl");
    XProcDocument source = ctx.input("source");
    Map<QName, XdmValue> params = DocumentSerializer.toParams(ctx.option("serialization"));
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ctx.pc().serializer.serialize(source, bos, params);
    byte[] bytes = bos.toByteArray();

    Xvrl report = new Xvrl("JAXP", null).document(source.getBaseURI());
    try {
      SAXParserFactory f = SAXParserFactory.newDefaultInstance();
      f.setNamespaceAware(true);
      f.setValidating(true);
      XMLReader reader = f.newSAXParser().getXMLReader();
      reader.setErrorHandler(new Collector(report));
      reader.setEntityResolver(new Resolver(ctx, List.of()));
      InputSource is = new InputSource(new ByteArrayInputStream(bytes));
      if (source.getBaseURI() != null) {
        is.setSystemId(source.getBaseURI().toString());
      }
      reader.parse(is);
    } catch (SAXException | IOException | javax.xml.parsers.ParserConfigurationException e) {
      if (report.isValid()) {
        report.add(new Detection("fatal-error", e.getMessage(), href(source), -1, -1, null,
            null));
      }
    }
    if (!bytesHaveDoctype(bytes) && report.isValid()) {
      report.add(Detection.error("The document has no document type declaration",
          href(source), -1, -1));
    }
    boolean valid = report.isValid();
    XProcDocument result = source;
    if (valid) {
      XdmNode doc = parse(ctx.processor(), bytes, source.getBaseURI(), true);
      Map<QName, XdmValue> props = new LinkedHashMap<>();
      if (source.getBaseURI() != null) {
        props.put(Ns.BASE_URI, new XdmAtomicValue(source.getBaseURI()));
      }
      XdmValue ser = source.getProperty(SERIALIZATION);
      if (ser != null) {
        props.put(SERIALIZATION, ser);
      }
      result = XProcDocument.of(ctx.processor(), doc, MediaType.XML, props);
    }
    finish(ctx, report.toDocument(ctx.processor()), valid, 210, result);
  }

  private static boolean bytesHaveDoctype(byte[] bytes) {
    String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
    return head.contains("<!DOCTYPE");
  }

  // =========================================================================================
  // JSON Schema (networknt)

  private static void jsonSchema(StepContext ctx) {
    reportFormat(ctx, "xvrl");
    XProcDocument source = ctx.input("source");
    XProcDocument schemaDoc = ctx.input("schema");
    String sourceJson = ctx.pc().serializer.serializeToString(source, Map.of());
    String schemaJson = ctx.pc().serializer.serializeToString(schemaDoc, Map.of());

    tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    tools.jackson.databind.JsonNode schemaNode;
    tools.jackson.databind.JsonNode instance;
    try {
      schemaNode = mapper.readTree(schemaJson);
      instance = mapper.readTree(sourceJson);
    } catch (tools.jackson.core.JacksonException e) {
      throw XProcException.xc(164, "Cannot read the JSON documents: " + e.getMessage());
    }
    SpecificationVersion version;
    tools.jackson.databind.JsonNode declared = schemaNode.isObject()
        ? schemaNode.get("$schema") : null;
    if (declared != null) {
      version = SpecificationVersion.fromDialectId(declared.asString()).orElseThrow(
          () -> XProcException.xc(163, "JSON Schema version " + declared.asString()
              + " is not supported"));
    } else {
      version = version(Xdm.string(ctx.option("default-version")));
    }

    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(version);
    // The schema must itself be valid against the meta-schema of its version
    try {
      var meta = registry.getSchema(SchemaLocation.of(version.getDialectId()));
      var metaErrors = meta.validate(schemaNode);
      if (!metaErrors.isEmpty()) {
        throw XProcException.xc(164, "The schema is not a valid JSON Schema: "
            + metaErrors.get(0).getMessage());
      }
    } catch (SchemaException e) {
      throw XProcException.xc(164, "The schema is not a valid JSON Schema: " + e.getMessage());
    }
    List<com.networknt.schema.Error> errors;
    try {
      com.networknt.schema.Schema schema = schemaDoc.getBaseURI() == null
          ? registry.getSchema(schemaNode)
          : registry.getSchema(SchemaLocation.of(schemaDoc.getBaseURI().toString()),
              schemaJson, InputFormat.JSON);
      errors = schema.validate(instance);
    } catch (SchemaException e) {
      throw XProcException.xc(164, "The schema is not a valid JSON Schema: " + e.getMessage());
    }
    Xvrl report = new Xvrl("networknt json-schema-validator", null)
        .document(source.getBaseURI()).schema(schemaDoc.getBaseURI(), version.getDialectId());
    for (com.networknt.schema.Error e : errors) {
      report.add(new Detection("error", e.getMessage(), href(source), -1, -1,
          e.getInstanceLocation() == null ? null : e.getInstanceLocation().toString(),
          e.getKeyword()));
    }
    finish(ctx, report.toDocument(ctx.processor()), errors.isEmpty(), 165, source);
  }

  /** The JSON Schema version named by default-version (default 2020-12). */
  private static SpecificationVersion version(String name) {
    if (name == null) {
      return SpecificationVersion.DRAFT_2020_12;
    }
    var byId = SpecificationVersion.fromDialectId(name);
    if (byId.isPresent()) {
      return byId.get();
    }
    return switch (name.strip().toLowerCase().replace("draft-", "").replace("draft", "")) {
      case "4", "04" -> SpecificationVersion.DRAFT_4;
      case "6", "06" -> SpecificationVersion.DRAFT_6;
      case "7", "07" -> SpecificationVersion.DRAFT_7;
      case "2019-09" -> SpecificationVersion.DRAFT_2019_09;
      case "2020-12" -> SpecificationVersion.DRAFT_2020_12;
      default -> throw XProcException.xc(163, "JSON Schema version " + name
          + " is not supported");
    };
  }
}
