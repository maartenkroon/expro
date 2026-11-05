package nl.armatiek.xproc.internal.runtime;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmArray;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmFunctionItem;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.str.StringView;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.model.AtomicStep;
import nl.armatiek.xproc.internal.model.Binder;
import nl.armatiek.xproc.internal.model.Binding;
import nl.armatiek.xproc.internal.model.Connection;
import nl.armatiek.xproc.internal.model.Container;
import nl.armatiek.xproc.internal.model.Node;
import nl.armatiek.xproc.internal.model.OptionDecl;
import nl.armatiek.xproc.internal.model.OptionValue;
import nl.armatiek.xproc.internal.model.PortDecl;
import nl.armatiek.xproc.internal.model.StepDeclaration;
import nl.armatiek.xproc.internal.model.StepNode;
import nl.armatiek.xproc.internal.model.ValueTemplate;
import nl.armatiek.xproc.internal.model.VariableNode;
import nl.armatiek.xproc.internal.model.XPathExpr;
import nl.armatiek.xproc.internal.steps.StepContext;
import nl.armatiek.xproc.internal.steps.StepImplementation;
import nl.armatiek.xproc.internal.util.QNames;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.xpath.XPathEngine.ContextState;

/** Evaluates pipelines. */
public final class Executor {

  /** The context item for an expression. */
  record Ctx(XdmItem item, ContextState state, List<XdmItem> collection) {
    static final Ctx UNDEFINED = new Ctx(null, ContextState.UNDEFINED, null);
  }

  private final ProcessorContext pc;
  private final InlineEvaluator inlines;

  public Executor(ProcessorContext pc) {
    this.pc = pc;
    this.inlines = new InlineEvaluator(pc);
  }

  // ---------------------------------------------------------------------------------------
  // Pipelines

  /**
   * Runs a declared pipeline as the top-level pipeline.
   *
   * @param inputs documents for (some of) the input ports
   * @param options values for (some of) the options; values are converted to the declared type
   */
  public Map<String, List<XProcDocument>> runTopLevel(StepDeclaration decl,
      Map<String, List<XProcDocument>> inputs, Map<QName, XdmValue> options) {
    Map<String, List<XProcDocument>> in = new LinkedHashMap<>();
    for (PortDecl port : decl.inputs.values()) {
      List<XProcDocument> docs = inputs.get(port.name);
      if (docs == null) {
        docs = port.connection == null ? List.of()
            : evalConnection(port.connection, new Frame(null), null);
      }
      in.put(port.name, docs);
    }
    for (String name : inputs.keySet()) {
      if (!decl.inputs.containsKey(name)) {
        throw XProcException.xs(114, "The pipeline has no input port named '" + name + "'")
            .setCallerError(true);
      }
    }
    Map<OptionDecl, BoundValue> opts = new LinkedHashMap<>();
    Frame optFrame = new Frame(null);
    for (OptionDecl od : decl.options.values()) {
      BoundValue bv;
      if (od.isStatic) {
        bv = new BoundValue(od.staticValue, od.staticNamespaces);
      } else if (options.containsKey(od.name)) {
        XdmValue v;
        try {
          v = pc.types.convert(options.get(od.name), od.asText,
              od.context == null ? Map.of() : od.context.prefixedNamespaces(),
              od.context == null ? null : od.context.baseUri);
          checkValues(od, v);
        } catch (XProcException e) {
          throw e.setCallerError(true);
        }
        bv = new BoundValue(v, od.context == null ? Map.of() : od.context.prefixedNamespaces());
      } else if (od.required) {
        throw XProcException.xs(18, "No value specified for required option '"
            + QNames.display(od.name) + "'").setCallerError(true);
      } else {
        bv = defaultOptionValue(od, optFrame);
      }
      optFrame.putValue(od, bv);
      opts.put(od, bv);
    }
    for (QName name : options.keySet()) {
      OptionDecl od = decl.options.get(name);
      if (od == null) {
        throw XProcException.xs(31, "The pipeline has no option named '" + QNames.display(name)
            + "'").setCallerError(true);
      }
    }
    return invokePipeline(decl, in, opts);
  }

  private Map<String, List<XProcDocument>> invokePipeline(StepDeclaration decl,
      Map<String, List<XProcDocument>> inputs, Map<OptionDecl, BoundValue> options) {
    if (decl.psviRequired) {
      throw XProcException.xd(22, "PSVI annotations are not supported by this processor");
    }
    Container body = decl.body;
    Frame f = new Frame(null);
    for (PortDecl port : decl.inputs.values()) {
      List<XProcDocument> docs = inputs.getOrDefault(port.name, List.of());
      checkPort(port, docs, true);
      register(docs);
      f.putPort(body, port.name, docs);
    }
    for (Map.Entry<OptionDecl, BoundValue> e : options.entrySet()) {
      f.putValue(e.getKey(), e.getValue());
    }
    runSubpipeline(body, f);
    Map<String, List<XProcDocument>> out = new LinkedHashMap<>();
    for (PortDecl port : decl.outputs.values()) {
      List<XProcDocument> docs = port.connection == null ? List.of()
          : evalConnection(port.connection, f, null);
      checkPort(port, docs, false);
      out.put(port.name, docs);
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------
  // Subpipelines

  void runSubpipeline(Container c, Frame f) {
    for (Node n : c.order) {
      runNode(n, f);
    }
  }

  private void runNode(Node n, Frame f) {
    if (n instanceof VariableNode v) {
      try {
        evalVariable(v, f);
      } catch (XProcException e) {
        throw v.location.apply(e);
      }
      return;
    }
    StepNode step = (StepNode) n;
    try {
      if (step.message != null) {
        String msg = evalAvt(step.message, contextFromPort(step.drp, f), f);
        pc.messages().message(MessageListener.Level.INFO, step.name, msg);
      }
      if (step instanceof AtomicStep a) {
        runAtomic(a, f);
      } else {
        runCompound((Container) step, f);
      }
    } catch (XProcException e) {
      throw step.location.apply(e);
    } catch (StackOverflowError e) {
      throw XProcException.xd(30, "Stack overflow (infinite recursion?) in step " + step.name);
    }
  }

  private void evalVariable(VariableNode v, Frame f) {
    List<XProcDocument> docs = v.connection == null ? null : evalConnection(v.connection, f,
        null);
    Ctx ctx = contextFromDocs(docs, v.collection);
    if (ctx.state() == ContextState.SEQUENCE) {
      // a sequence provides no context item for a variable (err:XD0001)
      ctx = Ctx.UNDEFINED;
    }
    XdmValue value = eval(v.select, ctx, f);
    Map<String, String> ns = namespacesOf(v.select, value, f);
    if (v.asText != null) {
      value = pc.types.convert(value, v.asText, v.select.context.prefixedNamespaces(),
          v.select.context.baseUri);
    }
    f.putValue(v, new BoundValue(value, ns));
  }

  // ---------------------------------------------------------------------------------------
  // Atomic steps

  private void runAtomic(AtomicStep s, Frame f) {
    StepDeclaration decl = s.decl;
    Map<String, List<XProcDocument>> inputs = new LinkedHashMap<>();
    for (PortDecl port : decl.inputs.values()) {
      Connection c = s.inputs.get(port.name);
      List<XProcDocument> docs = c == null ? List.of() : evalConnection(c, f, s);
      inputs.put(port.name, docs);
    }
    Map<OptionDecl, BoundValue> options = evalOptions(s, f);
    Map<String, List<XProcDocument>> outputs;
    try {
      if (decl.isExternal()) {
        for (PortDecl port : decl.inputs.values()) {
          checkPort(port, inputs.get(port.name), true);
        }
        outputs = runExternal(s, inputs, options);
        for (PortDecl port : decl.outputs.values()) {
          checkPort(port, outputs.get(port.name), false);
        }
      } else {
        outputs = invokePipeline(decl, inputs, options);
      }
    } catch (XProcException e) {
      throw e.inStep(s.name, s.type);
    }
    for (Map.Entry<String, List<XProcDocument>> e : outputs.entrySet()) {
      register(e.getValue());
      f.putPort(s, e.getKey(), e.getValue());
    }
  }

  private Map<String, List<XProcDocument>> runExternal(AtomicStep s,
      Map<String, List<XProcDocument>> inputs, Map<OptionDecl, BoundValue> options) {
    StepImplementation impl = pc.steps.create(s.type);
    if (impl == null) {
      throw XProcException.xd(17, "Don't know how to run step " + QNames.display(s.type));
    }
    Map<String, List<XProcDocument>> outputs = new LinkedHashMap<>();
    for (PortDecl port : s.decl.outputs.values()) {
      outputs.put(port.name, new ArrayList<>());
    }
    Map<QName, BoundValue> byName = new HashMap<>();
    for (Map.Entry<OptionDecl, BoundValue> e : options.entrySet()) {
      byName.put(e.getKey().name, e.getValue());
    }
    StepContext ctx = new StepContext() {
      @Override
      public ProcessorContext pc() {
        return pc;
      }

      @Override
      public String stepName() {
        return s.name;
      }

      @Override
      public QName stepType() {
        return s.type;
      }

      @Override
      public URI baseUri() {
        try {
          return s.element.getBaseURI();
        } catch (IllegalStateException e) {
          throw XProcException.xd(64, "Invalid base URI: " + e.getMessage());
        }
      }

      @Override
      public List<XProcDocument> inputs(String port) {
        List<XProcDocument> docs = inputs.get(port);
        return docs == null ? List.of() : docs;
      }

      @Override
      public XdmValue option(QName name) {
        BoundValue bv = byName.get(name);
        return bv == null || bv.value() == null ? XdmEmptySequence.getInstance() : bv.value();
      }

      @Override
      public Map<String, String> optionNamespaces(QName name) {
        BoundValue bv = byName.get(name);
        return bv == null || bv.namespaces() == null ? Map.of() : bv.namespaces();
      }

      @Override
      public void output(String port, XProcDocument doc) {
        List<XProcDocument> list = outputs.get(port);
        if (list == null) {
          throw new IllegalStateException("No output port " + port);
        }
        list.add(doc);
      }

      @Override
      public MessageListener messages() {
        return pc.messages();
      }

      @Override
      public StepDeclaration declaration() {
        return s.decl;
      }
    };
    try {
      impl.run(ctx);
    } catch (XProcException e) {
      throw e;
    } catch (RuntimeException e) {
      throw XProcException.xd(30, "Step " + QNames.display(s.type) + " failed: " + e, e);
    }
    return outputs;
  }

  private Map<OptionDecl, BoundValue> evalOptions(AtomicStep s, Frame f) {
    Map<OptionDecl, BoundValue> result = new LinkedHashMap<>();
    Frame callee = new Frame(null);
    for (OptionDecl od : s.decl.options.values()) {
      BoundValue bv;
      OptionValue ov = s.options.get(od.name);
      if (od.isStatic) {
        bv = new BoundValue(od.staticValue, od.staticNamespaces);
      } else if (ov != null) {
        bv = evalOptionValue(ov, od, f);
      } else {
        bv = defaultOptionValue(od, callee);
      }
      callee.putValue(od, bv);
      result.put(od, bv);
    }
    return result;
  }

  private BoundValue defaultOptionValue(OptionDecl od, Frame callee) {
    if (od.select == null) {
      XdmValue empty = XdmEmptySequence.getInstance();
      if (od.asText != null) {
        empty = pc.types.convert(empty, od.asText, od.context.prefixedNamespaces(), null);
      }
      return new BoundValue(empty, Map.of());
    }
    XdmValue v = eval(od.select, Ctx.UNDEFINED, callee);
    Map<String, String> ns = namespacesOf(od.select, v, callee);
    v = pc.types.convert(v, od.asText, od.context.prefixedNamespaces(), od.context.baseUri);
    checkValues(od, v);
    return new BoundValue(v, ns);
  }

  private BoundValue evalOptionValue(OptionValue ov, OptionDecl od, Frame f) {
    List<XProcDocument> docs = ov.connection == null ? null
        : evalConnection(ov.connection, f, null);
    Ctx ctx = contextFromDocs(docs, ov.collection);
    XdmValue v;
    Map<String, String> ns;
    if (ov.avt != null) {
      String s = evalAvt(ov.avt, ctx, f);
      v = XdmValue.wrap(net.sf.saxon.value.StringValue.makeUntypedAtomic(StringView.of(s)));
      ns = ov.context.prefixedNamespaces();
    } else {
      v = eval(ov.select, ctx, f);
      ns = namespacesOf(ov.select, v, f);
    }
    try {
      if (ov.asText != null) {
        v = pc.types.convert(v, ov.asText, ov.context.prefixedNamespaces(), ov.context.baseUri);
      }
      v = pc.types.convert(v, od.asText, od.context == null ? Map.of()
          : od.context.prefixedNamespaces(), ov.context.prefixedNamespaces(),
          od.noResolve ? null : ov.context.baseUri);
    } catch (XProcException e) {
      throw ov.location.apply(e);
    }
    checkValues(od, v);
    return new BoundValue(v, ns);
  }

  private void checkValues(OptionDecl od, XdmValue v) {
    if (od.values == null) {
      return;
    }
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    vars.put(new QName("v"), v);
    vars.put(new QName("values"), od.values);
    XdmValue ok = pc.xpath.evaluateForStep(
        "some $a in $values satisfies (count($v) = 1 and deep-equal($a, $v))", Map.of(), null,
        null, vars);
    if (!nl.armatiek.xproc.internal.util.Xdm.bool(ok)) {
      throw XProcException.xd(19, "Value '" + v + "' is not allowed for option '"
          + QNames.display(od.name) + "'");
    }
  }

  /** Namespace bindings that travel with a value (XProc 3.1, section 11.6). */
  private Map<String, String> namespacesOf(XPathExpr select, XdmValue value, Frame f) {
    if (select.singleVariable != null) {
      Binder b = select.context.bindings.get(select.singleVariable);
      if (b != null) {
        BoundValue bv = f.lookupValue(b);
        if (bv != null && bv.namespaces() != null) {
          return bv.namespaces();
        }
      }
    }
    if (value.size() > 0 && value.itemAt(0) instanceof XdmNode node) {
      XdmNode el = node.getNodeKind() == XdmNodeKind.ELEMENT ? node : node.getParent();
      if (el != null && el.getNodeKind() == XdmNodeKind.ELEMENT) {
        Map<String, String> m = new LinkedHashMap<>();
        for (XdmNode ns : el.axisIterator(Axis.NAMESPACE).stream().toList()) {
          String prefix = ns.getNodeName() == null ? "" : ns.getNodeName().getLocalName();
          if (!prefix.isEmpty()) {
            m.put(prefix, ns.getStringValue());
          }
        }
        return m;
      }
    }
    return select.context.prefixedNamespaces();
  }

  // ---------------------------------------------------------------------------------------
  // Compound steps

  private void runCompound(Container c, Frame f) {
    switch (c.kind) {
      case FOR_EACH -> runForEach(c, f);
      case VIEWPORT -> runViewport(c, f);
      case CHOOSE -> runChoose(c, f);
      case IF -> runIf(c, f);
      case GROUP -> runGroup(c, f);
      case TRY -> runTry(c, f);
      default -> throw new IllegalStateException("Unexpected container " + c.kind);
    }
  }

  private Map<String, List<XProcDocument>> containerOutputs(Container c, Frame inner) {
    Map<String, List<XProcDocument>> out = new LinkedHashMap<>();
    for (PortDecl port : c.outputs.values()) {
      List<XProcDocument> docs = port.connection == null ? List.of()
          : evalConnection(port.connection, inner, null);
      checkPort(port, docs, false);
      out.put(port.name, docs);
    }
    return out;
  }

  private void publish(Container c, Frame f, Map<String, List<XProcDocument>> outputs) {
    for (Map.Entry<String, List<XProcDocument>> e : outputs.entrySet()) {
      register(e.getValue());
      f.putPort(c, e.getKey(), e.getValue());
    }
  }

  private void runGroup(Container c, Frame f) {
    Frame inner = new Frame(f);
    runSubpipeline(c, inner);
    publish(c, f, containerOutputs(c, inner));
  }

  private void runForEach(Container c, Frame f) {
    List<XProcDocument> docs = evalConnection(c.source, f, null);
    Map<String, List<XProcDocument>> results = new LinkedHashMap<>();
    for (String port : c.outputs.keySet()) {
      results.put(port, new ArrayList<>());
    }
    int n = docs.size();
    for (int i = 0; i < n; i++) {
      Frame inner = new Frame(f);
      inner.iteration = new int[] {i + 1, n};
      inner.putPort(c, "current", List.of(docs.get(i)));
      register(List.of(docs.get(i)));
      runSubpipeline(c, inner);
      for (Map.Entry<String, List<XProcDocument>> e : containerOutputs(c, inner).entrySet()) {
        results.get(e.getKey()).addAll(e.getValue());
      }
    }
    publish(c, f, results);
  }

  private void runChoose(Container c, Frame f) {
    List<XProcDocument> chooseDocs = c.source != null ? evalConnection(c.source, f, null)
        : (c.drp != null ? lookup(c.drp, f) : null);
    Container selected = null;
    for (Container branch : c.branches) {
      if (branch.kind == Container.Kind.OTHERWISE) {
        selected = branch;
        break;
      }
      List<XProcDocument> docs = branch.source != null ? evalConnection(branch.source, f, null)
          : chooseDocs;
      Ctx ctx = testContext(docs, branch.collection);
      if (effectiveBoolean(eval(branch.test, ctx, f))) {
        selected = branch;
        break;
      }
    }
    Map<String, List<XProcDocument>> out = new LinkedHashMap<>();
    for (String port : c.outputs.keySet()) {
      out.put(port, List.of());
    }
    if (selected == null) {
      String primary = c.primaryOutput();
      if (primary != null && c.drp != null) {
        out.put(primary, lookup(c.drp, f));
      }
    } else {
      Frame inner = new Frame(f);
      runSubpipeline(selected, inner);
      out.putAll(containerOutputs(selected, inner));
    }
    publish(c, f, out);
  }

  private void runIf(Container c, Frame f) {
    List<XProcDocument> docs = c.source != null ? evalConnection(c.source, f, null)
        : (c.drp != null ? lookup(c.drp, f) : null);
    Ctx ctx = testContext(docs, c.collection);
    if (effectiveBoolean(eval(c.test, ctx, f))) {
      runGroup(c, f);
      return;
    }
    Map<String, List<XProcDocument>> out = new LinkedHashMap<>();
    for (String port : c.outputs.keySet()) {
      out.put(port, List.of());
    }
    String primary = c.primaryOutput();
    if (primary != null && c.drp != null) {
      out.put(primary, lookup(c.drp, f));
    }
    publish(c, f, out);
  }

  private void runTry(Container c, Frame f) {
    Container initial = c.branches.get(0);
    Map<String, List<XProcDocument>> out = new LinkedHashMap<>();
    for (String port : c.outputs.keySet()) {
      out.put(port, List.of());
    }
    XProcException failure = null;
    XdmNode errorDoc = null;
    try {
      Frame inner = new Frame(f);
      runSubpipeline(initial, inner);
      out.putAll(containerOutputs(initial, inner));
    } catch (XProcException e) {
      errorDoc = ErrorDocuments.create(pc.processor, e);
      Container handler = null;
      for (int i = 1; i < c.branches.size(); i++) {
        Container katch = c.branches.get(i);
        if (katch.codes.isEmpty() || katch.codes.contains(e.getCode())) {
          handler = katch;
          break;
        }
      }
      if (handler == null) {
        failure = e;
      } else {
        try {
          Frame inner = new Frame(f);
          XProcDocument err = XProcDocument.of(pc.processor, errorDoc, MediaType.XML,
              (URI) null);
          inner.putPort(handler, "error", List.of(err));
          register(List.of(err));
          runSubpipeline(handler, inner);
          for (String port : c.outputs.keySet()) {
            out.put(port, List.of());
          }
          out.putAll(containerOutputs(handler, inner));
        } catch (XProcException e2) {
          failure = e2;
        }
      }
    }
    if (c.finallyBranch != null) {
      Container fin = c.finallyBranch;
      Frame inner = new Frame(f);
      List<XProcDocument> err = errorDoc == null ? List.of()
          : List.of(XProcDocument.of(pc.processor, errorDoc, MediaType.XML, (URI) null));
      inner.putPort(fin, "error", err);
      register(err);
      try {
        runSubpipeline(fin, inner);
        out.putAll(containerOutputs(fin, inner));
      } catch (XProcException e) {
        if (failure == null) {
          failure = e;
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
    publish(c, f, out);
  }

  // ---------------------------------------------------------------------------------------
  // Viewport

  private void runViewport(Container c, Frame f) {
    List<XProcDocument> docs = evalConnection(c.source, f, null);
    List<XProcDocument> results = new ArrayList<>();
    String outPort = c.outputs.keySet().iterator().next();
    for (XProcDocument doc : docs) {
      if (doc.getKind() != MediaType.Kind.XML && doc.getKind() != MediaType.Kind.HTML) {
        throw XProcException.xd(72, "p:viewport can only process XML or HTML documents");
      }
      register(List.of(doc));
      XdmNode root = doc.getNode();
      List<XdmNode> matches = new ArrayList<>();
      findMatches(c, root, f, matches);
      int n = matches.size();
      Map<XdmNode, List<XProcDocument>> replacements = new HashMap<>();
      for (int i = 0; i < n; i++) {
        XdmNode m = matches.get(i);
        XProcDocument current;
        if (m.getNodeKind() == XdmNodeKind.DOCUMENT) {
          current = doc;
        } else {
          TreeWriter tw = new TreeWriter(pc.processor);
          tw.startDocument(m.getBaseURI());
          tw.addSubtree(m);
          XdmNode wrapped = tw.endDocument();
          current = XProcDocument.of(pc.processor, wrapped,
              m.getNodeKind() == XdmNodeKind.TEXT ? MediaType.TEXT : MediaType.XML,
              m.getBaseURI());
        }
        Frame inner = new Frame(f);
        inner.iteration = new int[] {i + 1, n};
        inner.putPort(c, "current", List.of(current));
        register(List.of(current));
        runSubpipeline(c, inner);
        List<XProcDocument> out = containerOutputs(c, inner).get(outPort);
        for (XProcDocument r : out) {
          MediaType.Kind k = r.getKind();
          if (k != MediaType.Kind.XML && k != MediaType.Kind.HTML && k != MediaType.Kind.TEXT) {
            throw XProcException.xd(73, "The viewport subpipeline returned a document that is "
                + "not XML, HTML or text");
          }
        }
        replacements.put(m, out);
      }
      if (matches.size() == 1 && matches.get(0).getNodeKind() == XdmNodeKind.DOCUMENT) {
        List<XProcDocument> out = replacements.get(matches.get(0));
        TreeWriter tw = new TreeWriter(pc.processor);
        tw.startDocument(root.getBaseURI());
        boolean allText = !out.isEmpty();
        for (XProcDocument r : out) {
          tw.addChildren(r.getNode());
          if (r.getKind() != MediaType.Kind.TEXT) {
            allText = false;
          }
        }
        XdmNode result = tw.endDocument();
        results.add(allText ? doc.withValue(pc.processor, result, MediaType.TEXT)
            : doc.withValue(pc.processor, result, doc.getContentType()));
        continue;
      }
      TreeWriter tw = new TreeWriter(pc.processor);
      tw.startDocument(root.getBaseURI());
      rebuild(tw, root, replacements);
      XdmNode result = tw.endDocument();
      results.add(doc.withValue(pc.processor, result, doc.getContentType()));
    }
    Map<String, List<XProcDocument>> out = new LinkedHashMap<>();
    out.put("result", results);
    publish(c, f, out);
  }

  private void findMatches(Container c, XdmNode node, Frame f, List<XdmNode> matches) {
    if (matchesPattern(c, node, f)) {
      matches.add(node);
      return;
    }
    if (node.getNodeKind() == XdmNodeKind.ELEMENT) {
      for (XdmNode attr : node.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
        if (matchesPattern(c, attr, f)) {
          throw XProcException.xd(10, "The viewport match pattern matched an attribute");
        }
      }
    }
    for (XdmNode child : node.children()) {
      findMatches(c, child, f, matches);
    }
  }

  private boolean matchesPattern(Container c, XdmNode node, Frame f) {
    XdmValue r = pc.xpath.evaluate(c.test, node, ContextState.DEFINED,
        f.variables(c.test.context.bindings), f.iteration(), null, false);
    return effectiveBoolean(r);
  }

  private void rebuild(TreeWriter tw, XdmNode node, Map<XdmNode, List<XProcDocument>> repl) {
    List<XProcDocument> r = repl.get(node);
    if (r != null) {
      for (XProcDocument d : r) {
        tw.addChildren(d.getNode());
      }
      return;
    }
    switch (node.getNodeKind()) {
      case DOCUMENT -> {
        for (XdmNode child : node.children()) {
          rebuild(tw, child, repl);
        }
      }
      case ELEMENT -> {
        if (!containsMatch(node, repl)) {
          tw.addSubtree(node);
          return;
        }
        tw.addStartElement(node);
        for (XdmNode attr : node.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
          tw.addAttribute(attr);
        }
        for (XdmNode child : node.children()) {
          rebuild(tw, child, repl);
        }
        tw.addEndElement();
      }
      default -> tw.addSubtree(node);
    }
  }

  private static boolean containsMatch(XdmNode node, Map<XdmNode, List<XProcDocument>> repl) {
    for (XdmNode m : repl.keySet()) {
      for (XdmNode p = m; p != null; p = p.getParent()) {
        if (p.equals(node)) {
          return true;
        }
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------
  // Connections

  List<XProcDocument> evalConnection(Connection c, Frame f, StepNode forStep) {
    List<XProcDocument> docs = new ArrayList<>();
    for (Binding b : c.bindings) {
      docs.addAll(evalBinding(b, f));
    }
    if (c.select != null && !docs.isEmpty()) {
      docs = applySelect(c.select, docs, f);
    }
    return docs;
  }

  private List<XProcDocument> lookup(Binding.Pipe p, Frame f) {
    List<XProcDocument> docs = f.lookupPort(p.producer(), p.port());
    return docs == null ? List.of() : docs;
  }

  private List<XProcDocument> evalBinding(Binding b, Frame f) {
    switch (b) {
      case Binding.Pipe p -> {
        return lookup(p, f);
      }
      case Binding.Empty e -> {
        return List.of();
      }
      case Binding.Inline in -> {
        Ctx ctx = contextFromPort(in.contextPort(), f);
        Map<QName, XdmValue> props = null;
        if (in.documentProperties() != null) {
          XdmValue pv = eval(in.documentProperties(), ctx, f);
          props = toQNameMap(pv, in.documentProperties().context.prefixedNamespaces());
        }
        XProcDocument d = inlines.evaluate(in, x -> evalTemplateExpr(x, ctx, f), props);
        return List.of(d);
      }
      case Binding.Document d -> {
        Ctx ctx = contextFromPort(d.contextPort(), f);
        String href = evalAvt(d.href(), ctx, f);
        URI uri;
        try {
          uri = d.context().baseUri == null ? new URI(href) : d.context().baseUri.resolve(
              new URI(href));
        } catch (java.net.URISyntaxException e) {
          throw XProcException.xd(64, "Invalid URI: " + href);
        }
        Map<QName, XdmValue> props = null;
        if (d.documentProperties() != null) {
          props = toQNameMap(eval(d.documentProperties(), ctx, f),
              d.context().prefixedNamespaces());
        }
        Map<QName, XdmValue> params = null;
        if (d.parameters() != null) {
          params = toQNameMap(eval(d.parameters(), ctx, f), d.context().prefixedNamespaces());
        }
        return List.of(pc.loader.load(uri, d.contentType(), params, props));
      }
    }
  }

  private XdmValue evalTemplateExpr(XPathExpr x, Ctx ctx, Frame f) {
    return eval(x, ctx, f);
  }

  /** Converts a map with string or QName keys into a Java map with QName keys. */
  public static Map<QName, XdmValue> toQNameMap(XdmValue v, Map<String, String> ns) {
    Map<QName, XdmValue> m = new LinkedHashMap<>();
    if (v == null || v.size() == 0) {
      return m;
    }
    if (!(v.itemAt(0) instanceof XdmMap map)) {
      throw XProcException.xd(36, "A map is required");
    }
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      Object u = e.getKey().getUnderlyingValue();
      QName k = u instanceof net.sf.saxon.value.QNameValue ? e.getKey().getQNameValue()
          : QNames.parseEQName(e.getKey().getStringValue(), ns, "XD", 61, 69);
      m.put(k, e.getValue());
    }
    return m;
  }

  private List<XProcDocument> applySelect(XPathExpr select, List<XProcDocument> docs,
      Frame f) {
    List<XProcDocument> out = new ArrayList<>();
    for (XProcDocument d : docs) {
      register(List.of(d));
      XdmItem item = d.getItem();
      Ctx ctx = item == null ? Ctx.UNDEFINED : new Ctx(item, ContextState.DEFINED, null);
      XdmValue r = eval(select, ctx, f);
      for (XdmItem i : r) {
        XProcDocument nd = itemToDocument(i, d, 16);
        if (nd != d) {
          Map<QName, XdmValue> props = new LinkedHashMap<>(nd.getProperties());
          for (Map.Entry<QName, XdmValue> e : d.getProperties().entrySet()) {
            if (!e.getKey().equals(Ns.CONTENT_TYPE) && !e.getKey().equals(Ns.BASE_URI)
                && !(e.getKey().equals(Ns.SERIALIZATION)
                    && !nd.getContentType().toBaseString().equals(d.getContentType()
                        .toBaseString()))) {
              props.putIfAbsent(e.getKey(), e.getValue());
            }
          }
          nd = nd.withProperties(pc.processor, props);
        }
        out.add(nd);
      }
    }
    return out;
  }

  /** Converts a result item into a document (XProc 3.1, section 3.3). */
  public XProcDocument itemToDocument(XdmItem item, XProcDocument source, int attrError) {
    return nl.armatiek.xproc.internal.util.Documents.fromItem(pc.processor, item, source,
        XProcException.xd(attrError, "Cannot create a document from an attribute, namespace "
            + "or function item"));
  }

  private void checkPort(PortDecl port, List<XProcDocument> docs, boolean input) {
    if (docs == null) {
      docs = List.of();
    }
    if (!port.sequence && docs.size() != 1) {
      String msg = (input ? "Input" : "Output") + " port '" + port.name + "' requires exactly "
          + "one document, but " + docs.size() + " documents appeared";
      throw input ? XProcException.xd(6, msg) : XProcException.xd(7, msg);
    }
    for (XProcDocument d : docs) {
      if (!MediaType.acceptable(port.contentTypes, d.getContentType())) {
        String msg = "Content type " + d.getContentType().toBaseString() + " is not allowed on "
            + (input ? "input" : "output") + " port '" + port.name + "'";
        throw input ? XProcException.xd(38, msg) : XProcException.xd(42, msg);
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Expressions

  private Ctx contextFromPort(Binding.Pipe port, Frame f) {
    if (port == null) {
      return Ctx.UNDEFINED;
    }
    return contextFromDocs(lookup(port, f), false);
  }

  private Ctx contextFromDocs(List<XProcDocument> docs, boolean collection) {
    if (docs == null) {
      // Without a default readable port the default collection is empty
      return collection ? new Ctx(null, ContextState.UNDEFINED, List.of()) : Ctx.UNDEFINED;
    }
    register(docs);
    if (collection) {
      List<XdmItem> items = new ArrayList<>();
      for (XProcDocument d : docs) {
        if (d.getItem() != null) {
          items.add(d.getItem());
        }
      }
      return new Ctx(null, ContextState.UNDEFINED, items);
    }
    if (docs.size() == 1) {
      XdmItem item = docs.get(0).getItem();
      return item == null ? Ctx.UNDEFINED : new Ctx(item, ContextState.DEFINED, null);
    }
    return new Ctx(null, docs.isEmpty() ? ContextState.UNDEFINED : ContextState.SEQUENCE, null);
  }

  /** Context for test expressions: undefined unless exactly one document. */
  private Ctx testContext(List<XProcDocument> docs, boolean collection) {
    Ctx ctx = contextFromDocs(docs, collection);
    if (ctx.state() == ContextState.SEQUENCE) {
      return Ctx.UNDEFINED;
    }
    return ctx;
  }

  private XdmValue eval(XPathExpr x, Ctx ctx, Frame f) {
    return pc.xpath.evaluate(x, ctx.item(), ctx.state(), f.variables(x.context.bindings),
        f.iteration(), ctx.collection(), false);
  }

  private String evalAvt(ValueTemplate t, Ctx ctx, Frame f) {
    StringBuilder sb = new StringBuilder();
    for (Object part : t.parts) {
      if (part instanceof String s) {
        sb.append(s);
      } else {
        sb.append(InlineEvaluator.atomizeToString(eval((XPathExpr) part, ctx, f)));
      }
    }
    return sb.toString();
  }

  private static boolean effectiveBoolean(XdmValue v) {
    if (v.size() == 0) {
      return false;
    }
    XdmItem first = v.itemAt(0);
    if (first instanceof XdmNode) {
      return true;
    }
    if (v.size() > 1) {
      throw XProcException.xd(83, "Effective boolean value is not defined for a sequence of "
          + "atomic values").setCauseCode(new QName("err",
              "http://www.w3.org/2005/xqt-errors", "FORG0006"));
    }
    if (first instanceof XdmAtomicValue av) {
      Object u = av.getUnderlyingValue();
      if (u instanceof net.sf.saxon.value.BooleanValue b) {
        return b.getBooleanValue();
      }
      if (u instanceof net.sf.saxon.value.NumericValue n) {
        double d = n.getDoubleValue();
        return d != 0 && !Double.isNaN(d);
      }
      return !av.getStringValue().isEmpty();
    }
    throw XProcException.xd(83, "Effective boolean value is not defined for this item");
  }

  private void register(List<XProcDocument> docs) {
    if (docs == null) {
      return;
    }
    for (XProcDocument d : docs) {
      pc.registry.register(d);
    }
  }
}
