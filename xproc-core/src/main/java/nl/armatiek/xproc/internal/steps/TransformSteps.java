package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.sf.saxon.s9api.Destination;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.RawDestination;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XQueryCompiler;
import net.sf.saxon.s9api.XQueryEvaluator;
import net.sf.saxon.s9api.XQueryExecutable;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.runtime.Executor;
import nl.armatiek.xproc.internal.util.Documents;
import nl.armatiek.xproc.internal.util.Xdm;
import nl.armatiek.xproc.internal.xpath.EvalState;

/** p:xslt and p:xquery. */
final class TransformSteps {

  private TransformSteps() {
  }

  static void register(StepRegistry r) {
    r.register("xslt", () -> TransformSteps::xslt);
    r.register("xquery", () -> TransformSteps::xquery);
  }

  private static List<XdmItem> items(List<XProcDocument> docs) {
    List<XdmItem> items = new ArrayList<>();
    for (XProcDocument d : docs) {
      items.add(d.getItem() == null ? d.getNode() : d.getItem());
    }
    return items;
  }

  // ---------------------------------------------------------------------------------------

  /** A destination that builds a tree or delivers raw results depending on build-tree. */
  static final class AdaptiveDestination extends net.sf.saxon.s9api.AbstractDestination {
    private Destination delegate;
    private java.util.Properties props = new java.util.Properties();

    @Override
    public net.sf.saxon.event.Receiver getReceiver(net.sf.saxon.event.PipelineConfiguration pipe,
        net.sf.saxon.serialize.SerializationProperties params) throws SaxonApiException {
      props = params.getProperties();
      String buildTree = props.getProperty("build-tree");
      String method = props.getProperty("method");
      boolean tree = buildTree != null ? !"no".equals(buildTree)
          : !(method != null && (method.equals("json") || method.equals("adaptive")));
      if (tree) {
        XdmDestination xd = new XdmDestination();
        if (getDestinationBaseURI() != null) {
          xd.setBaseURI(getDestinationBaseURI());
        }
        delegate = xd;
      } else {
        delegate = new RawDestination();
      }
      delegate.setDestinationBaseURI(getDestinationBaseURI());
      return delegate.getReceiver(pipe, params);
    }

    @Override
    public void close() throws SaxonApiException {
      if (delegate != null) {
        delegate.close();
      }
    }

    @Override
    public void closeAndNotify() throws SaxonApiException {
      if (delegate != null) {
        delegate.closeAndNotify();
      }
      super.closeAndNotify();
    }

    /** The results as documents. */
    List<XProcDocument> documents(StepContext ctx) {
      List<XProcDocument> docs = new ArrayList<>();
      if (delegate instanceof XdmDestination xd) {
        XdmNode n = xd.getXdmNode();
        if (n != null) {
          docs.add(treeResult(ctx, n, props.getProperty("method"),
              serializationProperties(props)));
        }
      } else if (delegate instanceof RawDestination rd) {
        XdmValue raw = rd.getXdmValue();
        if (raw != null) {
          for (XdmItem item : raw) {
            docs.add(Documents.fromItem(ctx.processor(), item, null,
                XProcException.xc(95, "The transformation returned an attribute or function")));
          }
        }
      }
      return docs;
    }
  }

  private static double xsltVersion(StepContext ctx, XProcDocument stylesheet) {
    String version = Xdm.string(ctx.option("version"));
    if (version == null) {
      XdmNode root = null;
      for (XdmNode c : stylesheet.getNode().children()) {
        if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
          root = c;
        }
      }
      if (root != null) {
        version = Ns.XSLT.equals(root.getNodeName().getNamespace())
            ? root.getAttributeValue(new QName("version"))
            : root.getAttributeValue(new QName(Ns.XSLT, "version"));
      }
    }
    if (version == null) {
      return 3.0;
    }
    double v;
    try {
      v = Double.parseDouble(version.trim());
    } catch (NumberFormatException e) {
      throw XProcException.xc(38, "Invalid XSLT version: " + version);
    }
    if (v != 1.0 && v != 2.0 && v != 3.0) {
      throw XProcException.xc(38, "XSLT version " + version + " is not available");
    }
    return v;
  }

  static void xslt(StepContext ctx) {
    List<XProcDocument> sources = ctx.inputs("source");
    XProcDocument stylesheet = ctx.input("stylesheet");
    double version = xsltVersion(ctx, stylesheet);
    Map<QName, XdmValue> params = Executor.toQNameMap(ctx.option("parameters"),
        ctx.optionNamespaces("parameters"));
    if (version == 1.0) {
      if (sources.size() != 1 || (sources.get(0).getKind() != MediaType.Kind.XML
          && sources.get(0).getKind() != MediaType.Kind.HTML)) {
        throw XProcException.xc(39, "XSLT 1.0 requires exactly one XML or HTML document");
      }
      for (XdmValue v : params.values()) {
        for (XdmItem i : v) {
          if (i instanceof net.sf.saxon.s9api.XdmFunctionItem) {
            throw XProcException.xc(105, "XSLT 1.0 parameters must be atomic values or nodes");
          }
        }
      }
    } else if (version == 2.0) {
      for (XProcDocument d : sources) {
        MediaType.Kind k = d.getKind();
        if (k != MediaType.Kind.XML && k != MediaType.Kind.HTML && k != MediaType.Kind.TEXT) {
          throw XProcException.xc(94, "XSLT 2.0 can only process XML, HTML and text documents");
        }
      }
      for (XdmValue v : params.values()) {
        for (XdmItem i : v) {
          if (i instanceof net.sf.saxon.s9api.XdmFunctionItem) {
            throw XProcException.xc(7, "XSLT 2.0 parameters cannot be maps, arrays or "
                + "functions");
          }
        }
      }
    }
    Map<QName, XdmValue> staticParams = Executor.toQNameMap(ctx.option("static-parameters"),
        ctx.optionNamespaces("static-parameters"));
    XsltCompiler compiler = ctx.processor().newXsltCompiler();
    if (version >= 3.0) {
      for (Map.Entry<QName, XdmValue> e : staticParams.entrySet()) {
        compiler.setParameter(e.getKey(), e.getValue());
      }
    }
    List<String> compileErrors = new ArrayList<>();
    compiler.setErrorReporter(err -> {
      if (!err.isWarning()) {
        compileErrors.add(err.getMessage());
      }
    });
    XsltExecutable exec;
    try {
      exec = compiler.compile(stylesheet.getNode().asSource());
    } catch (SaxonApiException e) {
      throw XProcException.xc(93, "Static error in XSLT stylesheet: " + (compileErrors.isEmpty()
          ? e.getMessage() : String.join("; ", compileErrors)), e).setCauseCode(e.getErrorCode());
    }
    Xslt30Transformer t = exec.load30();
    t.setMessageHandler(m -> ctx.messages().message(MessageListener.Level.INFO, ctx.stepName(),
        m.getStringValue()));
    List<AdaptiveDestination> secondary = new ArrayList<>();
    t.setResultDocumentHandler(uri -> {
      AdaptiveDestination d = new AdaptiveDestination();
      d.setDestinationBaseURI(uri);
      secondary.add(d);
      return d;
    });
    String outBase = Xdm.string(ctx.option("output-base-uri"));
    if (outBase == null) {
      URI b = !sources.isEmpty() ? sources.get(0).getBaseURI() : stylesheet.getBaseURI();
      outBase = b == null ? null : b.toString();
    }
    if (outBase != null) {
      t.setBaseOutputURI(outBase);
    }
    boolean populate = ctx.option("populate-default-collection").size() == 0
        || Xdm.bool(ctx.option("populate-default-collection"));
    XdmValue templateName = version == 1.0 ? XdmEmptySequenceHolder.EMPTY
        : ctx.option("template-name");
    XdmValue initialMode = version == 1.0 ? XdmEmptySequenceHolder.EMPTY
        : ctx.option("initial-mode");
    XdmValue globalContext = version >= 3.0 ? ctx.option("global-context-item")
        : XdmEmptySequenceHolder.EMPTY;
    for (XProcDocument d : sources) {
      ctx.pc().registry.register(d);
    }
    List<XdmItem> srcItems = items(sources);
    List<XdmItem> prevCollection = EvalState.STEP_COLLECTION.get();
    EvalState.STEP_COLLECTION.set(populate ? srcItems : null);
    AdaptiveDestination dest = new AdaptiveDestination();
    if (outBase != null) {
      dest.setDestinationBaseURI(URI.create(outBase));
    }
    try {
      t.setStylesheetParameters(params);
      if (globalContext.size() > 0) {
        t.setGlobalContextItem(globalContext.itemAt(0));
      } else if (sources.size() == 1 || (version < 3.0 && !sources.isEmpty())) {
        t.setGlobalContextItem(srcItems.get(0));
      }
      if (templateName.size() > 0) {
        QName tn = ((XdmAtomicValue) templateName.itemAt(0)).getQNameValue();
        t.callTemplate(tn, dest);
      } else {
        if (initialMode.size() > 0) {
          try {
            t.setInitialMode(((XdmAtomicValue) initialMode.itemAt(0)).getQNameValue());
          } catch (IllegalArgumentException | SaxonApiException e) {
            throw XProcException.xc(8, "The stylesheet does not support mode "
                + initialMode.itemAt(0).getStringValue());
          }
        }
        XdmValue selection = version < 3.0 && !srcItems.isEmpty()
            ? srcItems.get(0) : new XdmValue(srcItems);
        t.applyTemplates(selection, dest);
      }
    } catch (SaxonApiException e) {
      throw transformError(e, templateName);
    } finally {
      EvalState.STEP_COLLECTION.set(prevCollection);
    }
    for (XProcDocument d : dest.documents(ctx)) {
      ctx.output("result", d);
    }
    for (AdaptiveDestination d : secondary) {
      URI base = d.getDestinationBaseURI();
      if (base != null && !base.isAbsolute()) {
        throw XProcException.xc(121, "Secondary result has a relative base URI: " + base);
      }
      for (XProcDocument doc : d.documents(ctx)) {
        if (doc.getBaseURI() == null && base != null) {
          doc = doc.withProperty(ctx.processor(), Ns.BASE_URI, new XdmAtomicValue(base));
        }
        ctx.output("secondary", doc);
      }
    }
  }

  /** Holder for the empty sequence. */
  static final class XdmEmptySequenceHolder {
    static final XdmValue EMPTY = net.sf.saxon.s9api.XdmEmptySequence.getInstance();
  }

  private static XProcException transformError(SaxonApiException e, XdmValue templateName) {
    QName code = e.getErrorCode();
    String local = code == null ? "" : code.getLocalName();
    if ("XTDE0040".equals(local) && templateName.size() > 0) {
      return XProcException.xc(56, "The stylesheet has no template named "
          + templateName.itemAt(0).getStringValue());
    }
    if ("XTDE0044".equals(local) || "XTDE0045".equals(local)) {
      return XProcException.xc(8, "Mode error: " + e.getMessage());
    }
    if ("XTMM9000".equals(local) || isTermination(e)) {
      return XProcException.xc(96, "Transformation terminated by xsl:message: "
          + e.getMessage(), e).setCauseCode(code);
    }
    if (code != null && !local.startsWith("XT") && !local.startsWith("XP")
        && !local.startsWith("FO") && !local.startsWith("SE")) {
      // a user defined error (xsl:message terminate with error-code, fn:error)
      return new XProcException(code, e.getMessage(), e);
    }
    return XProcException.xc(95, "Error during XSLT transformation: " + e.getMessage(), e)
        .setCauseCode(code);
  }

  private static boolean isTermination(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t.getClass().getSimpleName().contains("Terminat")) {
        return true;
      }
    }
    return false;
  }

  private static Map<QName, XdmValue> serializationProperties(Properties props) {
    Map<QName, XdmValue> m = new LinkedHashMap<>();
    for (String key : props.stringPropertyNames()) {
      if (key.startsWith("{") || key.equals("build-tree")) {
        continue;
      }
      String v = props.getProperty(key);
      switch (key) {
        case "indent", "omit-xml-declaration", "byte-order-mark", "escape-uri-attributes",
            "include-content-type", "undeclare-prefixes", "allow-duplicate-names" ->
          m.put(new QName(key), new XdmAtomicValue("yes".equals(v) || "true".equals(v)
              || "1".equals(v)));
        default -> m.put(new QName(key), new XdmAtomicValue(v));
      }
    }
    return m;
  }

  private static XProcDocument treeResult(StepContext ctx, XdmNode doc, String method,
      Map<QName, XdmValue> ser) {
    MediaType ct;
    if (StepSupport.isTextOnly(doc) || "text".equals(method)) {
      ct = MediaType.TEXT;
    } else if ("html".equals(method)) {
      ct = MediaType.HTML;
    } else if ("xhtml".equals(method)) {
      ct = MediaType.XHTML;
    } else {
      ct = MediaType.XML;
    }
    Map<QName, XdmValue> props = new LinkedHashMap<>();
    URI base = doc.getBaseURI();
    if (base != null && !base.toString().isEmpty()) {
      props.put(Ns.BASE_URI, new XdmAtomicValue(base));
    }
    if (!ser.isEmpty() && ct != MediaType.TEXT) {
      Map<XdmAtomicValue, XdmValue> sm = new LinkedHashMap<>();
      for (Map.Entry<QName, XdmValue> e : ser.entrySet()) {
        sm.put(new XdmAtomicValue(e.getKey()), e.getValue());
      }
      props.put(Ns.SERIALIZATION, new net.sf.saxon.s9api.XdmMap(sm));
    }
    return XProcDocument.of(ctx.processor(), doc, ct, props);
  }

  // ---------------------------------------------------------------------------------------

  static void xquery(StepContext ctx) {
    List<XProcDocument> sources = ctx.inputs("source");
    XProcDocument queryDoc = ctx.input("query");
    String version = Xdm.string(ctx.option("version"));
    if (version != null && !List.of("1.0", "3.0", "3.1", "4.0").contains(version.trim())) {
      throw XProcException.xc(9, "XQuery version " + version + " is not available");
    }
    String query;
    if (queryDoc.getKind() == MediaType.Kind.TEXT) {
      query = StepSupport.text(queryDoc);
    } else {
      XdmNode root = null;
      for (XdmNode c : queryDoc.getNode().children()) {
        if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
          root = c;
        }
      }
      if (root != null && root.getNodeName().equals(Ns.c("query"))) {
        query = root.getStringValue();
      } else {
        query = ctx.pc().serializer.serializeToString(queryDoc, Map.of(
            new QName("omit-xml-declaration"), new XdmAtomicValue(true)));
      }
    }
    Map<QName, XdmValue> params = Executor.toQNameMap(ctx.option("parameters"),
        ctx.optionNamespaces("parameters"));
    if (version != null && (version.trim().equals("1.0") || version.trim().equals("3.0"))) {
      boolean v1 = version.trim().equals("1.0");
      for (XProcDocument d : sources) {
        XdmItem i = d.getItem();
        if (d.getKind() == MediaType.Kind.JSON && (v1 || i instanceof net.sf.saxon.s9api.XdmMap
            || i instanceof net.sf.saxon.s9api.XdmArray)) {
          throw XProcException.xc(101, "Document cannot be represented in the XDM of XQuery "
              + version);
        }
      }
      for (XdmValue v : params.values()) {
        for (XdmItem i : v) {
          if (i instanceof net.sf.saxon.s9api.XdmFunctionItem
              && (v1 || i instanceof net.sf.saxon.s9api.XdmMap
              || i instanceof net.sf.saxon.s9api.XdmArray)) {
            throw XProcException.xc(102, "Parameter cannot be represented in the XDM of XQuery "
                + version);
          }
        }
      }
    }
    XQueryCompiler compiler = ctx.processor().newXQueryCompiler();
    if (queryDoc.getBaseURI() != null) {
      compiler.setBaseURI(queryDoc.getBaseURI());
    }
    List<String> errors = new ArrayList<>();
    compiler.setErrorReporter(err -> {
      if (!err.isWarning()) {
        errors.add(err.getMessage());
      }
    });
    XQueryExecutable exec;
    try {
      exec = compiler.compile(query);
    } catch (SaxonApiException e) {
      throw XProcException.xc(103, "Static error in query: " + (errors.isEmpty()
          ? e.getMessage() : String.join("; ", errors)), e).setCauseCode(e.getErrorCode());
    }
    XQueryEvaluator ev = exec.load();
    for (XProcDocument d : sources) {
      ctx.pc().registry.register(d);
    }
    List<XdmItem> srcItems = items(sources);
    List<XdmItem> prevCollection = EvalState.STEP_COLLECTION.get();
    EvalState.STEP_COLLECTION.set(srcItems);
    XdmValue result;
    try {
      if (!srcItems.isEmpty()) {
        ev.setContextItem(srcItems.get(0));
      }
      for (Map.Entry<QName, XdmValue> e : params.entrySet()) {
        ev.setExternalVariable(e.getKey(), e.getValue());
      }
      result = ev.evaluate();
    } catch (SaxonApiException e) {
      QName code = e.getErrorCode();
      if (code != null && !code.getNamespace().equals(
          "http://www.w3.org/2005/xqt-errors")) {
        throw new XProcException(code, e.getMessage(), e);
      }
      throw XProcException.xc(104, "Error evaluating query: " + e.getMessage(), e)
          .setCauseCode(code);
    } finally {
      EvalState.STEP_COLLECTION.set(prevCollection);
    }
    for (XdmItem item : result) {
      XProcDocument d = Documents.fromItem(ctx.processor(), item, null,
          XProcException.xc(104, "The query returned an attribute or function item"));
      if (item instanceof XdmNode n && n.getNodeKind() == XdmNodeKind.DOCUMENT
          && StepSupport.isTextOnly(n)) {
        d = XProcDocument.of(ctx.processor(), n, MediaType.TEXT, n.getBaseURI());
      }
      ctx.output("result", d);
    }
  }
}
