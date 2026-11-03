package nl.armatiek.xproc.internal.parser;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltPackage;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.model.AtomicStep;
import nl.armatiek.xproc.internal.model.Binder;
import nl.armatiek.xproc.internal.model.Binding;
import nl.armatiek.xproc.internal.model.Connection;
import nl.armatiek.xproc.internal.model.Container;
import nl.armatiek.xproc.internal.model.ExprContext;
import nl.armatiek.xproc.internal.model.Location;
import nl.armatiek.xproc.internal.model.Node;
import nl.armatiek.xproc.internal.model.OptionDecl;
import nl.armatiek.xproc.internal.model.OptionValue;
import nl.armatiek.xproc.internal.model.PortDecl;
import nl.armatiek.xproc.internal.model.RunInfo;
import nl.armatiek.xproc.internal.model.StepDeclaration;
import nl.armatiek.xproc.internal.model.StepNode;
import nl.armatiek.xproc.internal.model.ValueTemplate;
import nl.armatiek.xproc.internal.model.VariableNode;
import nl.armatiek.xproc.internal.model.XPathExpr;
import nl.armatiek.xproc.internal.util.QNames;
import nl.armatiek.xproc.internal.xpath.XPathEngine.ContextState;

/**
 * Parses pipeline documents and performs static analysis, producing the pipeline model.
 */
public final class PipelineParser {

  private static final QName NAME = new QName("name");
  private static final QName TYPE = new QName("type");
  private static final QName PORT = new QName("port");
  private static final QName STEP = new QName("step");
  private static final QName HREF = new QName("href");
  private static final QName PIPE = new QName("pipe");
  private static final QName SELECT = new QName("select");
  private static final QName VERSION = new QName("version");
  private static final QName USE_WHEN = new QName("use-when");
  private static final QName P_USE_WHEN = Ns.p("use-when");
  private static final QName EXPAND_TEXT = new QName("expand-text");
  private static final QName P_EXPAND_TEXT = Ns.p("expand-text");
  private static final QName INLINE_EXPAND_TEXT = new QName("inline-expand-text");
  private static final QName P_INLINE_EXPAND_TEXT = Ns.p("inline-expand-text");
  private static final QName EXCLUDE_INLINE_PREFIXES = new QName("exclude-inline-prefixes");
  private static final QName E_TYPE = new QName("http://www.w3.org/1999/XSL/Spec/ElementSyntax",
      "type");

  private static final Set<String> STEP_COMMON = Set.of("name", "depends", "timeout", "message",
      "use-when", "expand-text");

  private final ProcessorContext pc;
  private final Map<QName, XdmValue> staticOverrides;
  private final Map<String, Library> libraries = new HashMap<>();
  /** Parse-time information about declarations whose bodies still have to be parsed. */
  private final Map<StepDeclaration, DeclInfo> pending = new LinkedHashMap<>();
  private boolean standardMode;
  private final Map<XdmNode, Boolean> includedCache = new HashMap<>();
  private final Set<XdmNode> evaluatingUseWhen = new HashSet<>();
  /** Overrides p:step-available() while resolving use-when on sibling declarations. */
  private java.util.function.Predicate<QName> useWhenPredicate;

  /** Signals a use-when expression that depends on a not yet resolved declaration. */
  private static final class Unresolved extends XProcException {
    private static final long serialVersionUID = 1L;

    Unresolved() {
      super(errCode("XS", 115), "unresolved");
    }
  }

  /** Information needed to parse the body of a declaration. */
  private record DeclInfo(TypeScope scope, Map<QName, Binder> staticBindings,
      List<XsltPackage> functions, Set<String> excludedNs, boolean expandText,
      String defaultName, List<XdmNode> bodyElements, List<XdmNode> outputElements) {
  }

  /** The static environment while parsing a subpipeline. */
  private static final class Env {
    TypeScope types;
    Map<QName, Binder> bindings = new LinkedHashMap<>();
    /** Step names in scope: siblings, ancestors and siblings of ancestors. */
    Map<String, StepNode> readable = new HashMap<>();
    /** Ancestor containers (their inputs are readable). */
    Set<StepNode> ancestors = new HashSet<>();
    Binding.Pipe drp;
    boolean expandText = true;
    Set<String> excludedNs = new HashSet<>();
    List<XsltPackage> functions = new ArrayList<>();
    /** The node currently being parsed (receives references). */
    Node current;
    StepDeclaration decl;

    Env copy() {
      Env e = new Env();
      e.types = types;
      e.bindings = new LinkedHashMap<>(bindings);
      e.readable = new HashMap<>(readable);
      e.ancestors = new HashSet<>(ancestors);
      e.drp = drp;
      e.expandText = expandText;
      e.excludedNs = new HashSet<>(excludedNs);
      e.functions = new ArrayList<>(functions);
      e.current = current;
      e.decl = decl;
      return e;
    }
  }

  public PipelineParser(ProcessorContext pc, Map<QName, XdmValue> staticOverrides) {
    this.pc = pc;
    this.staticOverrides = staticOverrides == null ? Map.of() : staticOverrides;
  }

  // =========================================================================================
  // Entry points

  /** The resources holding the declarations of the standard and additional step libraries. */
  private static final List<String> STEP_LIBRARIES = List.of("steps.xpl", "file-steps.xpl",
      "os-steps.xpl", "validation-steps.xpl");

  /** Parses the standard step library declarations into the processor context. */
  public static void loadStandardLibrary(ProcessorContext pc) {
    PipelineParser parser = new PipelineParser(pc, Map.of());
    parser.standardMode = true;
    for (String lib : STEP_LIBRARIES) {
      try (InputStream in = PipelineParser.class.getResourceAsStream(
          "/nl/armatiek/xproc/" + lib)) {
        DocumentBuilder db = pc.processor.newDocumentBuilder();
        XdmNode doc = db.build(new StreamSource(in, "http://www.w3.org/ns/xproc/" + lib));
        XdmNode root = firstElement(doc);
        TypeScope scope = new TypeScope(null, Map.of());
        for (XdmNode child : root.children()) {
          if (child.getNodeKind() == XdmNodeKind.ELEMENT && isP(child, "declare-step")) {
            StepDeclaration d = parser.parseSignature(child, scope, new LinkedHashMap<>(),
                List.of(), new HashSet<>(), true, "!std", false);
            if (d.type.getLocalName().equals("namespace-rename")) {
              for (OptionDecl od : d.options.values()) {
                od.noResolve = true;
              }
            }
            pc.standardSteps.put(d.type, d);
          }
        }
      } catch (Exception e) {
        throw new IllegalStateException("Cannot load the step library " + lib, e);
      }
    }
  }

  /** Parses a pipeline document whose root is a p:declare-step. */
  public StepDeclaration parse(XdmNode document) {
    XdmNode root = document.getNodeKind() == XdmNodeKind.DOCUMENT ? firstElement(document)
        : document;
    if (root == null || !Ns.XPROC.equals(root.getNodeName().getNamespace())
        || !(isP(root, "declare-step") || isP(root, "library"))) {
      throw loc(root, XProcException.xs(59,
          "The document element of a pipeline must be p:declare-step or p:library"));
    }
    checkVersion(root, true);
    if (isP(root, "library")) {
      throw loc(root, XProcException.xs(59,
          "A p:library cannot be run; the pipeline document must be a p:declare-step"));
    }
    URI uri = baseUri(root);
    TypeScope top = new TypeScope(null, pc.standardSteps);
    Library self = new Library(uri, root, false);
    if (uri != null) {
      libraries.put(canonical(uri), self);
    }
    StepDeclaration main = parseSignature(root, top, new LinkedHashMap<>(), List.of(),
        new HashSet<>(), true, "!1", false);
    self.ownTypes.put(main.type == null ? new QName(Ns.EXT, "main") : main.type, main);
    self.loaded = true;
    parsePendingBodies();
    return main;
  }

  private void parsePendingBodies() {
    while (!pending.isEmpty()) {
      StepDeclaration d = pending.keySet().iterator().next();
      DeclInfo info = pending.remove(d);
      if (info.scope().parent != null) {
        for (Map.Entry<QName, StepDeclaration> e : info.scope().local.entrySet()) {
          StepDeclaration other = info.scope().parent.lookup(e.getKey());
          if (other != null && other != e.getValue() && !pc.standardSteps.containsKey(e.getKey())) {
            throw loc(e.getValue().element, XProcException.xs(36, "Step type "
                + QNames.display(e.getKey()) + " is declared more than once"));
          }
        }
      }
      parseBody(d, info);
    }
  }

  // =========================================================================================
  // Declarations

  /**
   * Parses the signature (prologue) of a p:declare-step and registers nested declarations. The
   * body is parsed later.
   */
  private StepDeclaration parseSignature(XdmNode el, TypeScope parentScope,
      Map<QName, Binder> inheritedStatic, List<XsltPackage> inheritedFunctions,
      Set<String> inheritedExcluded, boolean inheritedExpandText, String defaultName,
      boolean inLibrary) {
    checkAttributes(el, Set.of("name", "type", "psvi-required", "xpath-version",
        "exclude-inline-prefixes", "version", "visibility", "use-when", "expand-text"));
    if (el.getParent() != null && el.getParent().getNodeKind() == XdmNodeKind.DOCUMENT) {
      checkVersion(el, true);
    } else {
      checkVersion(el, false);
    }
    StepDeclaration d = new StepDeclaration(el);
    d.name = el.getAttributeValue(NAME);
    if (d.name != null && !QNames.isNCName(d.name)) {
      throw loc(el, XProcException.xs(77, "Invalid step name: " + d.name));
    }
    String typeAttr = el.getAttributeValue(TYPE);
    if (typeAttr != null) {
      d.type = parseQName(el, typeAttr, 77);
      if (!standardMode && (d.type.getNamespace().isEmpty()
          || Ns.XPROC.equals(d.type.getNamespace()))) {
        throw loc(el, XProcException.xs(25, "Invalid step type " + typeAttr
            + ": must be in a namespace other than the XProc namespace"));
      }
    }
    d.psviRequired = booleanAttr(el, "psvi-required", false);
    String xpv = el.getAttributeValue(new QName("xpath-version"));
    if (xpv != null) {
      checkXPathVersion(el, xpv);
    }
    d.privateVisibility = inLibrary && "private".equals(el.getAttributeValue(
        new QName("visibility")));
    String vis = el.getAttributeValue(new QName("visibility"));
    if (vis != null && !vis.equals("private") && !vis.equals("public")) {
      throw loc(el, XProcException.xs(77, "Invalid visibility: " + vis));
    }
    boolean expandText = expandTextOf(el, inheritedExpandText);
    Set<String> excluded = new HashSet<>(inheritedExcluded);
    excluded.addAll(excludedNamespaces(el));

    TypeScope scope = new TypeScope(parentScope, pc.standardSteps);
    Map<QName, Binder> staticBindings = new LinkedHashMap<>(inheritedStatic);
    List<XsltPackage> functions = new ArrayList<>(inheritedFunctions);

    checkNoText(el);
    registerForward(el, scope, staticBindings, d);
    int phase = 0; // 0 imports, 1 prologue, 2 declarations, 3 subpipeline
    List<XdmNode> outputs = new ArrayList<>();
    List<XdmNode> body = new ArrayList<>();
    Map<QName, Binder> optionBindings = new LinkedHashMap<>(staticBindings);
    boolean declsResolved = false;
    for (XdmNode child : el.children()) {
      if (!declsResolved && child.getNodeKind() == XdmNodeKind.ELEMENT
          && isP(child, "declare-step")) {
        resolveDeclarationUseWhen(el, staticBindings, scope, d);
        declsResolved = true;
      }
      if (child.getNodeKind() != XdmNodeKind.ELEMENT || isDocumentation(child)
          || !included(child, staticBindings, scope, d)) {
        continue;
      }
      String childDefault = defaultName + "." + positionOf(child, staticBindings, scope, d);
      if (isP(child, "import")) {
        if (phase > 0) {
          throw loc(child, XProcException.xs(100, "p:import must precede the prologue"));
        }
        Library lib = importLibrary(child, staticBindings);
        scope.imported.add(lib);
        addLibraryOptions(lib, staticBindings, child);
        optionBindings.putAll(staticBindings);
        continue;
      }
      if (isP(child, "import-functions")) {
        if (phase > 0) {
          throw loc(child, XProcException.xs(100,
              "p:import-functions must precede the prologue"));
        }
        // functions imported here take precedence over inherited ones
        functions.add(0, importFunctions(child));
        continue;
      }
      if (isP(child, "input") || isP(child, "output") || isP(child, "option")) {
        if (phase > 1) {
          throw loc(child, XProcException.xs(100, child.getNodeName().getLocalName()
              + " must appear in the prologue"));
        }
        phase = 1;
        if (isP(child, "input")) {
          PortDecl port = parseInputDecl(child, staticBindings, scope, functions, excluded,
              expandText, d);
          if (d.inputs.containsKey(port.name) || d.outputs.containsKey(port.name)) {
            throw loc(child, XProcException.xs(11, "Duplicate port name: " + port.name));
          }
          d.inputs.put(port.name, port);
        } else if (isP(child, "output")) {
          checkAttributes(child, Set.of("port", "sequence", "primary", "content-types", "href",
              "pipe", "exclude-inline-prefixes", "serialization", "use-when", "expand-text"));
          PortDecl port = parsePortCommon(child, false, false);
          if ("!result".equals(port.name)) {
            port = port.copy("result");
          }
          if (d.inputs.containsKey(port.name) || d.outputs.containsKey(port.name)) {
            throw loc(child, XProcException.xs(11, "Duplicate port name: " + port.name));
          }
          String ser = child.getAttributeValue(new QName("serialization"));
          if (ser != null) {
            port.serialization = compile(new XPathExpr(ser, exprContext(child, staticBindings,
                functions, scope, d)));
            validateSerialization(child, port.serialization, staticBindings);
          }
          d.outputs.put(port.name, port);
          outputs.add(child);
        } else {
          OptionDecl od = parseOptionDecl(child, optionBindings, functions, scope, d, false);
          if (d.options.containsKey(od.name)) {
            throw loc(child, XProcException.xs(4, "Duplicate option: "
                + QNames.display(od.name)));
          }
          if (od.isStatic) {
            if (staticBindings.containsKey(od.name)) {
              throw loc(child, XProcException.xs(88, "Static option "
                  + QNames.display(od.name) + " shadows another static option"));
            }
            evaluateStaticOption(od, staticBindings);
            staticBindings.put(od.name, od);
          } else if (staticBindings.get(od.name) instanceof OptionDecl s && s.isStatic) {
            throw loc(child, XProcException.xs(88, "Option " + QNames.display(od.name)
                + " shadows a static option"));
          }
          d.options.put(od.name, od);
          optionBindings.put(od.name, od);
        }
        continue;
      }
      if (isP(child, "declare-step")) {
        if (phase > 2) {
          throw loc(child, XProcException.xs(100,
              "p:declare-step must precede the subpipeline"));
        }
        phase = 2;
        StepDeclaration nested = parseSignature(child, scope, staticBindings, functions,
            excluded, expandText, childDefault, false);
        if (nested.type != null) {
          try {
            scope.declare(nested);
          } catch (XProcException e) {
            throw loc(child, e);
          }
        }
        continue;
      }
      phase = 3;
      body.add(child);
    }
    try {
      scope.checkConflicts();
    } catch (XProcException e) {
      throw loc(el, e);
    }
    finishPorts(el, d.inputs, true);
    finishPorts(el, d.outputs, false);
    // the type of a pipeline is visible inside itself
    if (d.type != null) {
      StepDeclaration same = scope.lookupHere(d.type);
      if (same == null) {
        scope.local.put(d.type, d);
      } else if (same != d) {
        throw loc(el, XProcException.xs(36, "Step type " + QNames.display(d.type)
            + " is declared more than once"));
      }
    }
    if (body.isEmpty()) {
      d.external = true;
      for (XdmNode o : outputs) {
        if (hasConnection(o)) {
          throw loc(o, XProcException.xs(29,
              "An output of an external step declaration cannot have a connection"));
        }
      }
      for (OptionDecl od : d.options.values()) {
        if (od.isStatic && !standardMode) {
          throw loc(el, XProcException.xs(100,
              "External step declarations cannot declare static options"));
        }
      }
    } else {
      d.external = false;
      pending.put(d, new DeclInfo(scope, staticBindings, functions, excluded, expandText,
          defaultName, body, outputs));
    }
    return d;
  }

  private void finishPorts(XdmNode el, Map<String, PortDecl> ports, boolean input) {
    int explicitPrimary = 0;
    for (PortDecl p : ports.values()) {
      if (Boolean.TRUE.equals(p.primarySpecified)) {
        explicitPrimary++;
      }
    }
    if (explicitPrimary > 1) {
      throw loc(el, input ? XProcException.xs(30, "More than one primary input port")
          : XProcException.xs(14, "More than one primary output port"));
    }
    if (explicitPrimary == 0 && ports.size() == 1) {
      PortDecl p = ports.values().iterator().next();
      if (p.primarySpecified == null) {
        p.primary = true;
      }
    }
  }

  private void parseBody(StepDeclaration d, DeclInfo info) {
    Container body = new Container(d.element, Container.Kind.PIPELINE);
    body.declaration = d;
    body.name = d.name != null ? d.name : info.defaultName();
    body.explicitName = d.name != null;
    body.type = d.type;
    for (PortDecl p : d.inputs.values()) {
      body.innerInputs.put(p.name, p);
    }
    d.body = body;
    d.underConstruction = true;
    Env env = new Env();
    env.types = info.scope();
    env.bindings = new LinkedHashMap<>(info.staticBindings());
    for (OptionDecl od : d.options.values()) {
      if (!od.isStatic) {
        env.bindings.put(od.name, od);
      }
    }
    env.functions = new ArrayList<>(info.functions());
    env.excludedNs = new HashSet<>(info.excludedNs());
    env.expandText = info.expandText();
    env.decl = d;
    env.readable.put(body.name, body);
    env.ancestors.add(body);
    PortDecl primaryIn = d.primaryInput();
    env.drp = primaryIn == null ? null : new Binding.Pipe(body, primaryIn.name);
    env.current = body;
    parseSubpipeline(body, info.bodyElements(), env, info.defaultName());
    // outputs
    Env outEnv = env.copy();
    for (Node n : body.children) {
      if (n instanceof StepNode s) {
        outEnv.readable.put(s.name, s);
      }
    }
    outEnv.drp = lastStepPort(body);
    for (XdmNode o : info.outputElements()) {
      String pn = o.getAttributeValue(PORT);
      PortDecl port = d.outputs.get(pn == null ? "result" : pn);
      port.connection = parseConnection(o, outEnv, ConnKind.PIPELINE_OUTPUT, body);
    }
    for (PortDecl port : d.outputs.values()) {
      if (port.connection == null || port.connection.isEmpty()) {
        if (port.primary) {
          port.connection = connectToLastStep(body, d.element);
        } else if (port.connection == null) {
          port.connection = new Connection();
        }
      }
      body.outputs.put(port.name, port);
    }
    body.refs.clear();
    d.underConstruction = false;
    d.bodyParsed = true;
  }

  /** The primary output of the last step of a subpipeline (the final default readable port). */
  private static Binding.Pipe lastStepPort(Container c) {
    StepNode last = c.lastStep();
    String primary = last == null ? null : outsidePrimary(last);
    return primary == null ? null : new Binding.Pipe(last, primary);
  }

  private Connection connectToLastStep(Container c, XdmNode el) {
    StepNode last = c.lastStep();
    String primary = last == null ? null : outsidePrimary(last);
    if (primary == null) {
      throw loc(el, XProcException.xs(6, "The primary output port is not connected and the "
          + "last step in the subpipeline has no primary output port"));
    }
    Connection conn = new Connection(new Binding.Pipe(last, primary));
    c.refs.add(last);
    return conn;
  }

  private PortDecl parseInputDecl(XdmNode el, Map<QName, Binder> staticBindings,
      TypeScope scope, List<XsltPackage> functions, Set<String> excluded, boolean expandText,
      StepDeclaration d) {
    checkAttributes(el, Set.of("port", "sequence", "primary", "select", "content-types", "href",
        "exclude-inline-prefixes", "use-when", "expand-text"));
    PortDecl port = parsePortCommon(el, true, true);
    Env env = new Env();
    env.types = scope;
    env.bindings = new LinkedHashMap<>(staticBindings);
    env.functions = new ArrayList<>(functions);
    env.excludedNs = new HashSet<>(excluded);
    env.expandText = expandTextOf(el, expandText);
    env.decl = d;
    if (hasConnection(el)) {
      port.connection = parseConnection(el, env, ConnKind.INPUT_DECL, null);
    }
    String select = el.getAttributeValue(SELECT);
    if (select != null) {
      if (port.connection == null) {
        port.connection = new Connection();
      }
      port.connection.select = compile(new XPathExpr(select, exprContext(el, staticBindings,
          functions, scope, d)));
    }
    return port;
  }

  private PortDecl parsePortCommon(XdmNode el, boolean input, boolean portRequired) {
    if (!hasImplicitInline(el)) {
      checkNoText(el);
    }
    String name = el.getAttributeValue(PORT);
    if (name == null) {
      if (portRequired) {
        throw loc(el, XProcException.xs(38, "Missing required attribute 'port'"));
      }
      name = "!result";
    } else if (!QNames.isNCName(name)) {
      throw loc(el, XProcException.xs(77, "Invalid port name: " + name));
    }
    PortDecl p = new PortDecl(name, input);
    p.location = Location.of(el);
    p.sequence = booleanAttr(el, "sequence", false);
    String primary = el.getAttributeValue(new QName("primary"));
    if (primary != null) {
      p.primarySpecified = parseBoolean(el, "primary", primary);
      p.primary = p.primarySpecified;
    }
    String ct = el.getAttributeValue(new QName("content-types"));
    if (ct != null) {
      try {
        p.contentTypes = MediaType.parseContentTypes(ct);
      } catch (XProcException e) {
        if (e.getCode().getLocalName().equals("XD0079")) {
          throw loc(el, XProcException.xs(111, e.getMessage()));
        }
        throw loc(el, e);
      }
      p.contentTypesText = ct;
    }
    return p;
  }

  private OptionDecl parseOptionDecl(XdmNode el, Map<QName, Binder> bindings,
      List<XsltPackage> functions, TypeScope scope, StepDeclaration d, boolean inLibrary) {
    checkAttributes(el, Set.of("name", "as", "values", "static", "required", "select",
        "visibility", "use-when", "expand-text"));
    String nameAttr = requiredAttr(el, "name");
    QName name = parseVarName(el, nameAttr);
    OptionDecl od = new OptionDecl(name);
    od.location = Location.of(el);
    od.isStatic = booleanAttr(el, "static", false);
    od.required = booleanAttr(el, "required", false);
    String vis = el.getAttributeValue(new QName("visibility"));
    if (vis != null && !vis.equals("private") && !vis.equals("public")) {
      throw loc(el, XProcException.xs(77, "Invalid visibility: " + vis));
    }
    od.privateVisibility = inLibrary && "private".equals(vis);
    od.specialType = el.getAttributeValue(E_TYPE);
    od.context = exprContext(el, bindings, functions, scope, d);
    String as = el.getAttributeValue(new QName("as"));
    if (as != null) {
      od.asText = as.trim();
      try {
        pc.types.checkType(od.asText, od.context.prefixedNamespaces());
      } catch (XProcException e) {
        throw loc(el, e);
      }
    }
    String values = el.getAttributeValue(new QName("values"));
    if (values != null) {
      try {
        XdmValue v = pc.xpath.evaluateForStep(values, od.context.prefixedNamespaces(),
            od.context.baseUri, null, null);
        for (XdmItem item : v) {
          if (!(item instanceof XdmAtomicValue)) {
            throw XProcException.xs(101, "values must be a sequence of atomic values");
          }
        }
        od.values = v;
      } catch (XProcException e) {
        throw loc(el, e.getCode().getLocalName().equals("XS0101") ? e
            : XProcException.xs(101, "Invalid values attribute: " + e.getMessage()));
      }
    }
    String select = el.getAttributeValue(SELECT);
    if (od.required && select != null) {
      throw loc(el, XProcException.xs(17, "An option cannot be both required and have a default"));
    }
    if (od.required && od.isStatic) {
      throw loc(el, XProcException.xs(95, "An option cannot be both required and static"));
    }
    if (select != null) {
      od.select = compile(new XPathExpr(select, od.context));
    }
    return od;
  }

  /** Checks the types of well-known serialization parameters (err:XD0020). */
  private void validateSerialization(XdmNode el, XPathExpr expr, Map<QName, Binder> statics) {
    if (expr.deferredError != null) {
      return;
    }
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    for (Map.Entry<QName, Binder> e : statics.entrySet()) {
      if (e.getValue().isStatic()) {
        vars.put(e.getKey(), e.getValue().getStaticValue());
      }
    }
    if (!vars.keySet().containsAll(expr.declaredVariables)) {
      return;
    }
    XdmValue v;
    try {
      v = pc.xpath.evaluate(expr, null, ContextState.UNDEFINED, vars, null, null, true);
    } catch (XProcException e) {
      return;
    }
    Map<QName, XdmValue> params;
    try {
      params = nl.armatiek.xproc.internal.io.DocumentSerializer.toParams(v);
    } catch (XProcException e) {
      throw loc(el, XProcException.xd(20, "Serialization parameters must be a map"));
    }
    for (Map.Entry<QName, XdmValue> e : params.entrySet()) {
      if (!e.getKey().getNamespace().isEmpty()) {
        continue;
      }
      for (XdmItem item : e.getValue()) {
        boolean ok = switch (e.getKey().getLocalName()) {
          case "cdata-section-elements", "suppress-indentation", "method" ->
            item instanceof XdmAtomicValue av && (av.getUnderlyingValue()
                instanceof net.sf.saxon.value.StringValue || av.getUnderlyingValue()
                instanceof net.sf.saxon.value.QNameValue);
          case "indent", "omit-xml-declaration", "byte-order-mark", "escape-uri-attributes",
              "include-content-type", "undeclare-prefixes" ->
            item instanceof XdmAtomicValue av && (av.getUnderlyingValue()
                instanceof net.sf.saxon.value.BooleanValue || List.of("true", "false", "yes",
                    "no", "1", "0").contains(av.getStringValue().trim()));
          default -> true;
        };
        if (!ok) {
          throw loc(el, XProcException.xd(20, "Invalid value for serialization parameter "
              + e.getKey().getLocalName()));
        }
      }
    }
  }

  private void evaluateStaticOption(OptionDecl od, Map<QName, Binder> bindings) {
    XdmValue value;
    Map<String, String> ns = od.context.prefixedNamespaces();
    if (staticOverrides.containsKey(od.name)) {
      value = staticOverrides.get(od.name);
    } else if (pc.config.getStaticOptions().containsKey(od.name)) {
      value = pc.config.getStaticOptions().get(od.name);
    } else if (od.select != null) {
      Map<QName, XdmValue> vars = new LinkedHashMap<>();
      for (Map.Entry<QName, Binder> e : od.select.context.bindings.entrySet()) {
        if (e.getValue().isStatic()) {
          vars.put(e.getKey(), e.getValue().getStaticValue());
        }
      }
      try {
        value = pc.xpath.evaluate(od.select, null, ContextState.UNDEFINED, vars, null, null,
            true);
      } catch (XProcException e) {
        throw od.location.apply(e);
      }
    } else {
      value = net.sf.saxon.s9api.XdmEmptySequence.getInstance();
    }
    try {
      value = pc.types.convert(value, od.asText, ns, od.context.baseUri);
    } catch (XProcException e) {
      throw od.location.apply(e);
    }
    od.staticValue = value;
    od.staticNamespaces = ns;
  }

  // =========================================================================================
  // Imports

  private Library importLibrary(XdmNode el, Map<QName, Binder> staticBindings) {
    checkAttributes(el, Set.of("href", "use-when"));
    String href = requiredAttr(el, "href");
    URI uri;
    try {
      URI base = baseUri(el);
      uri = base == null ? new URI(href) : base.resolve(new URI(href));
    } catch (Exception e) {
      throw loc(el, XProcException.xs(52, "Invalid import URI: " + href));
    }
    String key = canonical(uri);
    Library lib = libraries.get(key);
    if (lib != null) {
      return lib;
    }
    XdmNode root;
    try {
      var res = pc.loader.read(uri);
      XdmNode doc = pc.loader.parseXml(res.bytes(), res.uri(), null, null);
      root = firstElement(doc);
    } catch (XProcException e) {
      throw loc(el, XProcException.xs(52, "Cannot import " + uri + ": " + e.getMessage()));
    }
    if (root == null || !(isP(root, "library") || isP(root, "declare-step"))) {
      throw loc(el, XProcException.xs(52, "Imported document is not a p:library or "
          + "p:declare-step: " + uri));
    }
    checkVersion(root, true);
    boolean isLib = isP(root, "library");
    lib = new Library(uri, root, isLib);
    libraries.put(key, lib);
    if (isLib) {
      loadLibrary(lib);
    } else {
      TypeScope scope = new TypeScope(null, pc.standardSteps);
      StepDeclaration d = parseSignature(root, scope, new LinkedHashMap<>(), List.of(),
          new HashSet<>(), true, "!1", false);
      if (d.type != null) {
        lib.ownTypes.put(d.type, d);
      }
      lib.scope = scope;
    }
    lib.loaded = true;
    return lib;
  }

  private void loadLibrary(Library lib) {
    XdmNode root = lib.root;
    checkAttributes(root, Set.of("psvi-required", "xpath-version", "exclude-inline-prefixes",
        "version", "use-when", "expand-text"));
    String xpv = root.getAttributeValue(new QName("xpath-version"));
    if (xpv != null) {
      checkXPathVersion(root, xpv);
    }
    boolean psvi = booleanAttr(root, "psvi-required", false);
    TypeScope scope = new TypeScope(null, pc.standardSteps);
    lib.scope = scope;
    Map<QName, Binder> staticBindings = new LinkedHashMap<>();
    List<XsltPackage> functions = new ArrayList<>();
    Set<String> excluded = excludedNamespaces(root);
    boolean expandText = expandTextOf(root, true);
    int phase = 0;
    checkNoText(root);
    registerForward(root, scope, staticBindings, null);
    boolean declsResolved = false;
    for (XdmNode child : root.children()) {
      if (!declsResolved && child.getNodeKind() == XdmNodeKind.ELEMENT
          && isP(child, "declare-step")) {
        resolveDeclarationUseWhen(root, staticBindings, scope, null);
        declsResolved = true;
      }
      if (child.getNodeKind() != XdmNodeKind.ELEMENT || isDocumentation(child)
          || !included(child, staticBindings, scope, null)) {
        continue;
      }
      String dflt = "!1." + positionOf(child, staticBindings, scope, null);
      if (isP(child, "import")) {
        if (phase > 0) {
          throw loc(child, XProcException.xs(100, "p:import must precede the prologue"));
        }
        Library imp = importLibrary(child, staticBindings);
        lib.imports.add(imp);
        scope.imported.add(imp);
        addLibraryOptions(imp, staticBindings, child);
      } else if (isP(child, "import-functions")) {
        if (phase > 0) {
          throw loc(child, XProcException.xs(100,
              "p:import-functions must precede the prologue"));
        }
        functions.add(importFunctions(child));
      } else if (isP(child, "option")) {
        if (phase > 1) {
          throw loc(child, XProcException.xs(100, "p:option must precede the declarations"));
        }
        phase = 1;
        OptionDecl od = parseOptionDecl(child, staticBindings, functions, scope, null, true);
        if (!od.isStatic) {
          throw loc(child, XProcException.xs(109,
              "Options in a p:library must be static"));
        }
        if (lib.ownOptions.containsKey(od.name)) {
          throw loc(child, XProcException.xs(71, "Duplicate static option "
              + QNames.display(od.name)));
        }
        if (staticBindings.containsKey(od.name)) {
          throw loc(child, XProcException.xs(88, "Static option " + QNames.display(od.name)
              + " shadows another static option"));
        }
        evaluateStaticOption(od, staticBindings);
        staticBindings.put(od.name, od);
        lib.ownOptions.put(od.name, od);
      } else if (isP(child, "declare-step")) {
        phase = 2;
        StepDeclaration d = parseSignature(child, scope, staticBindings, functions, excluded,
            expandText, dflt, true);
        if (psvi && child.getAttributeValue(new QName("psvi-required")) == null) {
          d.psviRequired = true;
        }
        if (d.type != null) {
          try {
            scope.declare(d);
          } catch (XProcException e) {
            throw loc(child, e);
          }
          lib.ownTypes.put(d.type, d);
        }
      } else {
        throw loc(child, XProcException.xs(100, "Unexpected element in p:library: "
            + child.getNodeName()));
      }
    }
    try {
      scope.checkConflicts();
    } catch (XProcException e) {
      throw loc(root, e);
    }
  }

  private void addLibraryOptions(Library lib, Map<QName, Binder> staticBindings, XdmNode at) {
    for (Map.Entry<QName, OptionDecl> e : lib.visibleOptions().entrySet()) {
      Binder prev = staticBindings.get(e.getKey());
      if (prev != null && prev != e.getValue()) {
        throw loc(at, XProcException.xs(71, "Static option " + QNames.display(e.getKey())
            + " is declared more than once"));
      }
      staticBindings.put(e.getKey(), e.getValue());
    }
  }

  private XsltPackage importFunctions(XdmNode el) {
    checkAttributes(el, Set.of("href", "content-type", "namespace", "use-when"));
    String href = requiredAttr(el, "href");
    String ct = el.getAttributeValue(new QName("content-type"));
    URI uri = baseUri(el) == null ? URI.create(href) : baseUri(el).resolve(href);
    if (ct == null) {
      String guess = nl.armatiek.xproc.internal.io.DocumentLoader.contentTypeForName(
          uri.getPath());
      ct = guess == null ? "application/xslt+xml" : guess;
    }
    if (!(ct.equals("application/xslt+xml") || ct.equals("text/xsl")
        || ct.equals("application/xml"))) {
      throw loc(el, XProcException.xs(104, "Cannot import function libraries of type " + ct));
    }
    byte[] bytes;
    try {
      bytes = pc.loader.read(uri).bytes();
    } catch (XProcException e) {
      throw loc(el, XProcException.xs(103, "Cannot retrieve function library " + uri));
    }
    XsltCompiler xc = pc.processor.newXsltCompiler();
    try {
      return xc.compilePackage(new StreamSource(new java.io.ByteArrayInputStream(bytes),
          uri.toString()));
    } catch (SaxonApiException e) {
      throw loc(el, XProcException.xs(106, "Cannot load function library " + uri + ": "
          + e.getMessage()));
    }
  }

  // =========================================================================================
  // Subpipelines

  private enum ConnKind {
    WITH_INPUT, INPUT_DECL, COMPOUND_OUTPUT, PIPELINE_OUTPUT, OPTION, VARIABLE, CONTEXT
  }

  /** Parses the steps (and variables) of a subpipeline into a container. */
  private void parseSubpipeline(Container c, List<XdmNode> elements, Env outer,
      String defaultPrefix) {
    // pass 1: create step shells so that sibling names are known
    List<Object[]> items = new ArrayList<>();
    Map<String, StepNode> siblings = new LinkedHashMap<>();
    for (XdmNode el : elements) {
      String dflt = defaultPrefix + "." + positionOf(el, outer.bindings, outer.types,
          outer.decl);
      if (isP(el, "variable")) {
        items.add(new Object[] {el, null, dflt});
        continue;
      }
      StepNode s = shell(el, outer, dflt);
      if (s.explicitName) {
        if (siblings.containsKey(s.name) || outer.readable.containsKey(s.name)) {
          throw loc(el, XProcException.xs(2, "Duplicate step name: " + s.name));
        }
      }
      siblings.put(s.name, s);
      items.add(new Object[] {el, s, dflt});
    }
    boolean hasStep = !siblings.isEmpty();
    if (!hasStep) {
      throw loc(c.element, XProcException.xs(15, "A subpipeline must contain at least one step"));
    }
    Env env = outer.copy();
    env.readable.putAll(siblings);
    int index = 0;
    for (Object[] item : items) {
      XdmNode el = (XdmNode) item[0];
      if (item[1] == null) {
        VariableNode v = parseVariable(el, env);
        v.parent = c;
        v.index = index++;
        c.children.add(v);
        env.bindings.put(v.name, v);
        continue;
      }
      StepNode s = (StepNode) item[1];
      s.parent = c;
      s.index = index++;
      s.drp = env.drp;
      Env stepEnv = env.copy();
      stepEnv.current = s;
      // a step cannot read its own outputs
      stepEnv.readable.remove(s.name);
      if (s instanceof AtomicStep a) {
        parseAtomic(a, el, stepEnv);
      } else {
        parseCompound((Container) s, el, stepEnv, (String) item[2]);
      }
      c.children.add(s);
      String primary = outsidePrimary(s);
      env.drp = primary == null ? null : new Binding.Pipe(s, primary);
    }
    // depends
    for (Node n : c.children) {
      if (n instanceof StepNode s) {
        for (String dep : s.dependsNames) {
          StepNode target = env.readable.get(dep);
          if (target == null || env.ancestors.contains(target)) {
            throw loc(s.element, XProcException.xs(73, "Unknown step in depends: " + dep));
          }
          s.refs.add(target);
        }
      }
    }
    computeOrder(c);
    for (Node n : c.children) {
      for (Object r : n.refs) {
        if (!(r instanceof Node rn && rn.parent == c) && r != c) {
          c.refs.add(r);
        }
      }
    }
  }

  /** Topological sort of the children of a container (document order breaks ties). */
  private void computeOrder(Container c) {
    Set<Node> siblings = new HashSet<>(c.children);
    for (Node n : c.children) {
      n.deps.clear();
      for (Object r : n.refs) {
        if (r instanceof Node rn && rn != n && siblings.contains(rn)) {
          n.deps.add(rn);
        }
      }
    }
    Map<Node, Integer> indegree = new HashMap<>();
    Map<Node, List<Node>> dependents = new HashMap<>();
    for (Node n : c.children) {
      indegree.put(n, n.deps.size());
      for (Node d : n.deps) {
        dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(n);
      }
    }
    PriorityQueue<Node> ready = new PriorityQueue<>((a, b) -> Integer.compare(a.index, b.index));
    for (Node n : c.children) {
      if (indegree.get(n) == 0) {
        ready.add(n);
      }
    }
    List<Node> order = new ArrayList<>();
    while (!ready.isEmpty()) {
      Node n = ready.poll();
      order.add(n);
      for (Node d : dependents.getOrDefault(n, List.of())) {
        int deg = indegree.merge(d, -1, Integer::sum);
        if (deg == 0) {
          ready.add(d);
        }
      }
    }
    if (order.size() != c.children.size()) {
      Node bad = null;
      for (Node n : c.children) {
        if (!order.contains(n)) {
          bad = n;
          break;
        }
      }
      throw loc(bad.element, XProcException.xs(1, "Loop in the connections between steps "
          + "(involving " + bad.displayName() + ")"));
    }
    c.order = order;
  }

  /** Creates a step node with name, type and (outside visible) output ports. */
  private StepNode shell(XdmNode el, Env env, String defaultName) {
    QName elName = el.getNodeName();
    StepNode s;
    if (Ns.XPROC.equals(elName.getNamespace())) {
      String local = elName.getLocalName();
      Container.Kind kind = switch (local) {
        case "for-each" -> Container.Kind.FOR_EACH;
        case "viewport" -> Container.Kind.VIEWPORT;
        case "choose" -> Container.Kind.CHOOSE;
        case "if" -> Container.Kind.IF;
        case "group" -> Container.Kind.GROUP;
        case "try" -> Container.Kind.TRY;
        default -> null;
      };
      if (kind != null) {
        s = new Container(el, kind);
      } else {
        switch (local) {
          case "when", "otherwise", "catch", "finally", "input", "output", "option",
              "with-input", "with-option", "declare-step", "import", "import-functions",
              "library", "pipe", "inline", "document", "empty" ->
            throw loc(el, XProcException.xs(100, "Element p:" + local
                + " is not allowed here"));
          default -> {
          }
        }
        s = new AtomicStep(el);
      }
    } else {
      s = new AtomicStep(el);
    }
    String name = el.getAttributeValue(NAME);
    if (name != null) {
      if (!QNames.isNCName(name)) {
        throw loc(el, XProcException.xs(77, "Invalid step name: " + name));
      }
      s.name = name;
      s.explicitName = true;
    } else {
      s.name = defaultName;
    }
    s.type = elName;
    if (s instanceof AtomicStep a) {
      StepDeclaration d = isP(el, "run") ? runDeclaration(el, env) : env.types.lookup(elName);
      if (d == null) {
        throw loc(el, XProcException.xs(44, "No declaration for step "
            + QNames.display(elName)));
      }
      a.decl = d;
      for (PortDecl p : d.outputs.values()) {
        a.outputs.put(p.name, p);
      }
    } else {
      computeShellOutputs((Container) s, el, env);
    }
    return s;
  }

  /** Determines the output port names of a compound step before it is parsed. */
  private void computeShellOutputs(Container c, XdmNode el, Env env) {
    Map<String, PortDecl> outs = shellOutputs(el, c.kind, env);
    c.outputs.putAll(outs);
  }

  private Map<String, PortDecl> shellOutputs(XdmNode el, Container.Kind kind, Env env) {
    Map<String, PortDecl> outs = new LinkedHashMap<>();
    List<XdmNode> children = elementChildren(el, env.bindings, env.types, env.decl);
    if (kind == Container.Kind.CHOOSE || kind == Container.Kind.TRY) {
      List<XdmNode> branches = new ArrayList<>();
      if (kind == Container.Kind.TRY) {
        branches.add(el);
      }
      for (XdmNode ch : children) {
        if (isP(ch, "when") || isP(ch, "otherwise") || isP(ch, "catch") || isP(ch, "finally")) {
          branches.add(ch);
        }
      }
      for (XdmNode b : branches) {
        Map<String, PortDecl> bo = shellOutputs(b, kind == Container.Kind.TRY && b == el
            ? Container.Kind.GROUP : Container.Kind.WHEN, env);
        for (PortDecl p : bo.values()) {
          if (!outs.containsKey(p.name)) {
            PortDecl q = p.copy(p.name);
            q.sequence = true;
            q.contentTypes = MediaType.parseContentTypes(null);
            q.primary = p.primary;
            outs.put(p.name, q);
          } else if (p.primary) {
            outs.get(p.name).primary = true;
          }
        }
      }
      return outs;
    }
    if (kind == Container.Kind.VIEWPORT) {
      PortDecl r = new PortDecl("result", false);
      r.sequence = true;
      r.primary = true;
      outs.put("result", r);
      return outs;
    }
    List<XdmNode> outputEls = new ArrayList<>();
    XdmNode lastStep = null;
    for (XdmNode ch : children) {
      if (isP(ch, "output")) {
        outputEls.add(ch);
      } else if (isStepElement(ch)) {
        lastStep = ch;
      }
    }
    if (!outputEls.isEmpty()) {
      int explicit = 0;
      for (XdmNode o : outputEls) {
        PortDecl p = parsePortCommon(o, false, false);
        if (outs.containsKey(p.name)) {
          throw loc(o, XProcException.xs(11, "Duplicate port name: " + p.name));
        }
        if (Boolean.TRUE.equals(p.primarySpecified)) {
          explicit++;
        }
        outs.put(p.name, p);
      }
      if (explicit > 1) {
        throw loc(el, XProcException.xs(14, "More than one primary output port"));
      }
      if (explicit == 0 && outs.size() == 1) {
        PortDecl p = outs.values().iterator().next();
        if (p.primarySpecified == null) {
          p.primary = true;
        }
      }
      if (kind == Container.Kind.FOR_EACH) {
        for (PortDecl p : outs.values()) {
          p.sequence = true;
        }
      }
      return outs;
    }
    if (lastStep != null && kind != Container.Kind.FINALLY) {
      String primary = shellPrimaryOutput(lastStep, env);
      if (primary != null) {
        PortDecl p = new PortDecl("!result", false);
        p.primary = true;
        p.sequence = true;
        outs.put(p.name, p);
      }
    }
    return outs;
  }

  private String shellPrimaryOutput(XdmNode stepEl, Env env) {
    QName n = stepEl.getNodeName();
    if (Ns.XPROC.equals(n.getNamespace())) {
      Container.Kind kind = switch (n.getLocalName()) {
        case "for-each" -> Container.Kind.FOR_EACH;
        case "viewport" -> Container.Kind.VIEWPORT;
        case "choose" -> Container.Kind.CHOOSE;
        case "if" -> Container.Kind.IF;
        case "group" -> Container.Kind.GROUP;
        case "try" -> Container.Kind.TRY;
        default -> null;
      };
      if (kind != null) {
        for (PortDecl p : shellOutputs(stepEl, kind, env).values()) {
          if (p.primary) {
            return p.name;
          }
        }
        return null;
      }
    }
    StepDeclaration d = isP(stepEl, "run") ? runDeclaration(stepEl, env) : env.types.lookup(n);
    if (d == null) {
      return null;
    }
    PortDecl p = d.primaryOutput();
    return p == null ? null : p.name;
  }

  /**
   * Builds the declaration of a p:run invocation from its p:run-input, p:run-option and
   * p:output children.
   */
  private StepDeclaration runDeclaration(XdmNode el, Env env) {
    StepDeclaration d = new StepDeclaration(el);
    d.type = el.getNodeName();
    d.external = true;
    d.bodyParsed = true;
    RunInfo run = new RunInfo();
    d.run = run;
    PortDecl pipeline = new PortDecl(RunInfo.PIPELINE_PORT, true);
    pipeline.primary = false;
    pipeline.primarySpecified = false;
    pipeline.contentTypes = MediaType.parseContentTypes("xml");
    d.inputs.put(pipeline.name, pipeline);
    List<XdmNode> runInputs = new ArrayList<>();
    List<XdmNode> outputs = new ArrayList<>();
    for (XdmNode child : elementChildren(el, env.bindings, env.types, env.decl)) {
      if (isP(child, "run-input")) {
        runInputs.add(child);
      } else if (isP(child, "output")) {
        outputs.add(child);
      } else if (isP(child, "run-option")) {
        QName name = parseQName(child, requiredAttr(child, "name"), 77);
        if (d.options.containsKey(name)) {
          throw loc(child, XProcException.xs(80, "Duplicate p:run-option for "
              + QNames.display(name)));
        }
        OptionDecl od = new OptionDecl(name);
        od.location = Location.of(child);
        d.options.put(name, od);
        if (booleanAttr(child, "static", false)) {
          run.staticOptions.add(name);
        }
      }
    }
    for (XdmNode ri : runInputs) {
      String port = requiredAttr(ri, "port");
      if (!QNames.isNCName(port)) {
        throw loc(ri, XProcException.xs(77, "Invalid port name: " + port));
      }
      if (d.inputs.containsKey(port)) {
        throw loc(ri, XProcException.xs(86, "Duplicate p:run-input for port " + port));
      }
      PortDecl p = new PortDecl(port, true);
      p.sequence = true;
      p.contentTypes = MediaType.parseContentTypes(null);
      String primary = ri.getAttributeValue(new QName("primary"));
      p.primary = primary == null ? runInputs.size() == 1 : booleanAttr(ri, "primary", false);
      p.primarySpecified = primary == null ? null : p.primary;
      if (p.primary) {
        if (run.primaryInput != null) {
          throw loc(ri, XProcException.xs(30, "More than one primary p:run-input"));
        }
        run.primaryInput = port;
      }
      run.inputs.add(port);
      d.inputs.put(port, p);
    }
    for (XdmNode o : outputs) {
      checkAttributes(o, Set.of("port", "primary", "sequence", "content-types",
          "serialization", "use-when"));
      if (hasConnection(o)) {
        throw loc(o, XProcException.xs(100, "An output of p:run cannot have a connection"));
      }
      PortDecl p = parsePortCommon(o, false, true);
      if (d.outputs.containsKey(p.name)) {
        throw loc(o, XProcException.xs(11, "Duplicate port name: " + p.name));
      }
      d.outputs.put(p.name, p);
    }
    finishPorts(el, d.outputs, false);
    PortDecl po = d.primaryOutput();
    run.primaryOutput = po == null ? null : po.name;
    return d;
  }

  private boolean isStepElement(XdmNode el) {
    QName n = el.getNodeName();
    if (!Ns.XPROC.equals(n.getNamespace())) {
      return true;
    }
    return switch (n.getLocalName()) {
      case "input", "output", "option", "variable", "with-input", "with-option", "when",
          "otherwise", "catch", "finally", "documentation", "pipeinfo", "declare-step",
          "import", "import-functions", "inline", "document", "pipe", "empty" -> false;
      default -> true;
    };
  }

  /** The primary output port of a step as seen from outside. */
  private static String outsidePrimary(StepNode s) {
    if (s instanceof Container c && c.kind == Container.Kind.VIEWPORT) {
      return "result";
    }
    return s.primaryOutput();
  }

  private static boolean hasOutsidePort(StepNode s, String port) {
    if (s instanceof Container c && c.kind == Container.Kind.VIEWPORT) {
      return "result".equals(port);
    }
    return s.outputs.containsKey(port);
  }

  // -----------------------------------------------------------------------------------------
  // Variables

  private VariableNode parseVariable(XdmNode el, Env env) {
    checkAttributes(el, Set.of("name", "as", "select", "collection", "href", "pipe",
        "exclude-inline-prefixes", "use-when", "expand-text"));
    QName name = parseVarName(el, requiredAttr(el, "name"));
    VariableNode v = new VariableNode(el, name);
    Binder prev = env.bindings.get(name);
    if (prev != null && prev.isStatic()) {
      throw loc(el, XProcException.xs(88, "Variable " + QNames.display(name)
          + " shadows a static option"));
    }
    String select = el.getAttributeValue(SELECT);
    if (select == null) {
      throw loc(el, XProcException.xs(94, "p:variable must have a select attribute"));
    }
    Env venv = env.copy();
    venv.current = v;
    venv.expandText = expandTextOf(el, env.expandText);
    v.collection = booleanAttr(el, "collection", false);
    v.asText = el.getAttributeValue(new QName("as"));
    ExprContext ctx = exprContext(el, env.bindings, env.functions, env.types, env.decl);
    if (v.asText != null) {
      try {
        pc.types.checkType(v.asText, ctx.prefixedNamespaces());
      } catch (XProcException e) {
        throw loc(el, e);
      }
    }
    v.select = compile(new XPathExpr(select, ctx));
    addVarRefs(v.select, venv);
    if (hasConnection(el)) {
      v.connection = parseConnection(el, venv, ConnKind.VARIABLE, null);
      v.explicitConnection = true;
    } else if (env.drp != null && (v.select.usesContext || v.collection)) {
      v.connection = new Connection(env.drp);
      v.refs.add(env.drp.producer());
    }
    return v;
  }

  // -----------------------------------------------------------------------------------------
  // Atomic steps

  private void parseAtomic(AtomicStep s, XdmNode el, Env env) {
    StepDeclaration decl = s.decl;
    boolean xprocElement = Ns.XPROC.equals(el.getNodeName().getNamespace());
    env.expandText = expandTextOf(el, env.expandText);
    parseStepCommon(s, el, env, xprocElement);
    // shortcuts
    Map<QName, XdmNode> shortcutAttrs = new LinkedHashMap<>();
    for (XdmNode attr : el.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
      QName an = attr.getNodeName();
      if (!an.getNamespace().isEmpty()) {
        if (Ns.XPROC.equals(an.getNamespace()) && xprocElement) {
          throw loc(el, XProcException.xs(97, "Attribute " + an + " is not allowed on "
              + el.getNodeName()));
        }
        if (!xprocElement && !Ns.XPROC.equals(an.getNamespace())
            && !Ns.XML.equals(an.getNamespace()) && decl.options.containsKey(an)) {
          // a namespaced option of an extension step
          shortcutAttrs.put(an, attr);
        }
        continue;
      }
      String local = an.getLocalName();
      if (local.equals("name")) {
        continue;
      }
      if (xprocElement && STEP_COMMON.contains(local)) {
        continue;
      }
      shortcutAttrs.put(an, attr);
    }
    // children
    Map<String, XdmNode> withInputs = new LinkedHashMap<>();
    Map<QName, XdmNode> withOptions = new LinkedHashMap<>();
    boolean isRun = decl.run != null;
    for (XdmNode child : elementChildren(el, env.bindings, env.types, env.decl)) {
      if (isRun && (isP(child, "with-input") || isP(child, "run-input"))) {
        String port = isP(child, "with-input") ? RunInfo.PIPELINE_PORT
            : child.getAttributeValue(PORT);
        if (isP(child, "with-input") && child.getAttributeValue(PORT) != null) {
          throw loc(child, XProcException.xs(100, "The p:with-input of p:run has no port "
              + "attribute"));
        }
        if (withInputs.containsKey(port)) {
          throw loc(child, XProcException.xs(86, "Duplicate input for port " + port));
        }
        withInputs.put(port, child);
      } else if (isRun && isP(child, "run-option")) {
        withOptions.put(parseQName(child, requiredAttr(child, "name"), 77), child);
      } else if (isRun && isP(child, "output")) {
        continue;
      } else if (isP(child, "with-input")) {
        String port = child.getAttributeValue(PORT);
        if (port == null) {
          PortDecl primary = decl.primaryInput();
          if (primary == null) {
            throw loc(child, XProcException.xs(65, "Step " + QNames.display(s.type)
                + " has no primary input port"));
          }
          port = primary.name;
        }
        if (!decl.inputs.containsKey(port)) {
          throw loc(child, XProcException.xs(114, "Step " + QNames.display(s.type)
              + " has no input port named '" + port + "'"));
        }
        if (withInputs.containsKey(port)) {
          throw loc(child, XProcException.xs(86, "Duplicate p:with-input for port " + port));
        }
        withInputs.put(port, child);
      } else if (isP(child, "with-option")) {
        QName name = parseQName(child, requiredAttr(child, "name"), 77);
        if (!decl.options.containsKey(name)) {
          throw loc(child, XProcException.xs(31, "Step " + QNames.display(s.type)
              + " has no option named " + QNames.display(name)));
        }
        if (withOptions.containsKey(name)) {
          throw loc(child, XProcException.xs(80, "Duplicate p:with-option for "
              + QNames.display(name)));
        }
        withOptions.put(name, child);
      } else {
        throw loc(child, XProcException.xs(100, "Element " + child.getNodeName()
            + " is not allowed in step " + QNames.display(s.type)));
      }
    }
    // inputs
    for (PortDecl port : decl.inputs.values()) {
      XdmNode wi = withInputs.get(port.name);
      Connection conn;
      if (wi != null && isP(wi, "run-input") && !port.primary && !hasConnection(wi)) {
        // A non-primary p:run-input without a connection is empty
        checkAttributes(wi, Set.of("port", "primary", "select", "exclude-inline-prefixes",
            "use-when", "expand-text"));
        conn = new Connection();
        conn.bindings.add(new Binding.Empty());
      } else if (wi != null) {
        checkAttributes(wi, isP(wi, "run-input") ? Set.of("port", "primary", "select", "href",
            "pipe", "exclude-inline-prefixes", "use-when", "expand-text") : Set.of("port",
            "select", "href", "pipe", "exclude-inline-prefixes", "use-when", "expand-text"));
        Env ienv = env.copy();
        ienv.expandText = expandTextOf(wi, env.expandText);
        conn = parseConnection(wi, ienv, ConnKind.WITH_INPUT, null);
        String select = wi.getAttributeValue(SELECT);
        if (select != null) {
          conn.select = compile(new XPathExpr(select, exprContext(wi, env.bindings,
              env.functions, env.types, env.decl)));
          addVarRefs(conn.select, env);
        }
      } else if (port.primary && env.drp != null) {
        conn = new Connection(env.drp);
        s.refs.add(env.drp.producer());
      } else if (port.connection != null) {
        conn = port.connection;
      } else if (port.primary) {
        throw loc(el, XProcException.xs(32, "No connection for primary input port '"
            + port.name + "' of step " + s.name));
      } else {
        throw loc(el, XProcException.xs(3, "Input port '" + port.name + "' of step "
            + s.name + " is not connected"));
      }
      s.inputs.put(port.name, conn);
    }
    // options
    for (Map.Entry<QName, XdmNode> e : shortcutAttrs.entrySet()) {
      QName name = e.getKey();
      OptionDecl od = decl.options.get(name);
      if (od == null) {
        throw loc(el, XProcException.xs(31, "Step " + QNames.display(s.type)
            + " has no option named " + name.getLocalName()));
      }
      if (withOptions.containsKey(name)) {
        throw loc(el, XProcException.xs(27, "Option " + name.getLocalName()
            + " is specified both as attribute and with p:with-option"));
      }
      if (od.isStatic) {
        throw loc(el, XProcException.xs(92, "Cannot set the value of static option "
            + QNames.display(name)));
      }
      OptionValue ov = new OptionValue();
      ov.location = Location.of(el);
      ov.context = exprContext(el, env.bindings, env.functions, env.types, env.decl);
      String value = e.getValue().getStringValue();
      boolean usesContext;
      if (od.isMapOrArrayType()) {
        ov.select = compile(new XPathExpr(value, ov.context));
        addVarRefs(ov.select, env);
        usesContext = ov.select.usesContext;
      } else {
        ov.avt = parseAvt(el, value, ov.context, env);
        usesContext = ov.avt.usesContext();
      }
      if (usesContext && env.drp != null) {
        ov.connection = new Connection(env.drp);
        s.refs.add(env.drp.producer());
      }
      s.options.put(name, ov);
    }
    for (Map.Entry<QName, XdmNode> e : withOptions.entrySet()) {
      XdmNode wo = e.getValue();
      checkAttributes(wo, isRun ? Set.of("name", "as", "select", "collection", "href", "pipe",
          "static", "exclude-inline-prefixes", "use-when", "expand-text") : Set.of("name", "as",
          "select", "collection", "href", "pipe", "exclude-inline-prefixes", "use-when",
          "expand-text"));
      OptionDecl od = decl.options.get(e.getKey());
      if (od.isStatic) {
        throw loc(wo, XProcException.xs(92, "Cannot set the value of static option "
            + QNames.display(e.getKey())));
      }
      String select = wo.getAttributeValue(SELECT);
      if (select == null) {
        throw loc(wo, XProcException.xs(38, "p:with-option requires a select attribute"));
      }
      OptionValue ov = new OptionValue();
      ov.location = Location.of(wo);
      ov.context = exprContext(wo, env.bindings, env.functions, env.types, env.decl);
      ov.select = compile(new XPathExpr(select, ov.context));
      addVarRefs(ov.select, env);
      ov.collection = booleanAttr(wo, "collection", false);
      ov.asText = wo.getAttributeValue(new QName("as"));
      if (ov.asText != null) {
        try {
          pc.types.checkType(ov.asText, ov.context.prefixedNamespaces());
        } catch (XProcException ex) {
          throw loc(wo, ex);
        }
      }
      Env oenv = env.copy();
      oenv.expandText = expandTextOf(wo, env.expandText);
      if (hasConnection(wo)) {
        ov.connection = parseConnection(wo, oenv, ConnKind.OPTION, null);
      } else if (env.drp != null && (ov.select.usesContext || ov.collection)) {
        ov.connection = new Connection(env.drp);
        s.refs.add(env.drp.producer());
      }
      s.options.put(e.getKey(), ov);
    }
    for (OptionDecl od : decl.options.values()) {
      if (od.required && !s.options.containsKey(od.name)) {
        throw loc(el, XProcException.xs(18, "Required option " + QNames.display(od.name)
            + " not specified on step " + QNames.display(s.type)));
      }
    }
  }

  /** Parses name-independent common attributes of a step invocation. */
  private void parseStepCommon(StepNode s, XdmNode el, Env env, boolean xprocElement) {
    String depends = el.getAttributeValue(xprocElement ? new QName("depends")
        : Ns.p("depends"));
    if (depends != null) {
      if (depends.isBlank()) {
        throw loc(el, XProcException.xs(77, "The depends attribute must not be empty"));
      }
      for (String t : depends.trim().split("\\s+")) {
        if (!QNames.isNCName(t)) {
          throw loc(el, XProcException.xs(77, "Invalid step name in depends: " + t));
        }
        s.dependsNames.add(t);
      }
    }
    String timeout = el.getAttributeValue(xprocElement ? new QName("timeout")
        : Ns.p("timeout"));
    if (timeout != null) {
      checkTimeout(el, timeout);
    }
    String message = el.getAttributeValue(xprocElement ? new QName("message")
        : Ns.p("message"));
    if (message != null) {
      ExprContext ctx = exprContext(el, env.bindings, env.functions, env.types, env.decl);
      s.message = parseAvt(el, message, ctx, env);
      if (s.message.usesContext() && env.drp != null) {
        s.refs.add(env.drp.producer());
      }
    }
  }

  private void checkTimeout(XdmNode el, String value) {
    String v = value.trim();
    try {
      double d = Double.parseDouble(v);
      if (d < 0 || Double.isNaN(d)) {
        throw XProcException.xs(77, "Invalid timeout: " + value);
      }
      return;
    } catch (NumberFormatException e) {
      // try a duration
    }
    try {
      XdmValue r = pc.xpath.evaluateForStep("xs:dayTimeDuration($d) ge xs:dayTimeDuration('PT0S')",
          Map.of("xs", Ns.XS), null, null, Map.of(new QName("d"), new XdmAtomicValue(v)));
      if (!nl.armatiek.xproc.internal.util.Xdm.bool(r)) {
        throw XProcException.xs(77, "Invalid timeout: " + value);
      }
    } catch (XProcException e) {
      throw loc(el, XProcException.xs(77, "Invalid timeout: " + value));
    }
  }

  // -----------------------------------------------------------------------------------------
  // Compound steps

  private void parseCompound(Container c, XdmNode el, Env outer, String defaultName) {
    switch (c.kind) {
      case FOR_EACH -> checkAttributes(el, union(STEP_COMMON, Set.of()));
      case VIEWPORT -> checkAttributes(el, union(STEP_COMMON, Set.of("match")));
      case CHOOSE, GROUP, TRY -> checkAttributes(el, STEP_COMMON);
      case IF -> checkAttributes(el, union(STEP_COMMON, Set.of("test", "collection")));
      default -> {
      }
    }
    outer.expandText = expandTextOf(el, outer.expandText);
    parseStepCommon(c, el, outer, true);
    List<XdmNode> children = elementChildren(el, outer.bindings, outer.types, outer.decl);
    switch (c.kind) {
      case FOR_EACH, VIEWPORT, IF, GROUP -> parseSimpleCompound(c, el, children, outer,
          defaultName);
      case CHOOSE -> parseChoose(c, el, children, outer, defaultName);
      case TRY -> parseTry(c, el, children, outer, defaultName);
      default -> throw new IllegalStateException();
    }
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> s = new HashSet<>(a);
    s.addAll(b);
    return s;
  }

  /** for-each, viewport, if, group (and branches) share this structure. */
  private void parseSimpleCompound(Container c, XdmNode el, List<XdmNode> children, Env outer,
      String defaultName) {
    List<XdmNode> outputs = new ArrayList<>();
    List<XdmNode> body = new ArrayList<>();
    XdmNode withInput = null;
    boolean seenStep = false;
    for (XdmNode ch : children) {
      if (isP(ch, "with-input") && (c.kind == Container.Kind.FOR_EACH
          || c.kind == Container.Kind.VIEWPORT || c.kind == Container.Kind.IF
          || c.kind == Container.Kind.WHEN)) {
        if (seenStep || withInput != null) {
          throw loc(ch, XProcException.xs(100, "Misplaced p:with-input"));
        }
        if (ch.getAttributeValue(PORT) != null) {
          throw loc(ch, XProcException.xs(43, "A port cannot be specified on p:with-input of "
              + el.getNodeName()));
        }
        withInput = ch;
      } else if (isP(ch, "output")) {
        if (seenStep) {
          throw loc(ch, XProcException.xs(100, "p:output must precede the subpipeline"));
        }
        outputs.add(ch);
      } else {
        seenStep = true;
        body.add(ch);
      }
    }
    if (c.kind == Container.Kind.VIEWPORT && outputs.size() > 1) {
      throw loc(el, XProcException.xs(100, "p:viewport can have at most one output"));
    }
    boolean anyStep = false;
    for (XdmNode b : body) {
      anyStep |= !isP(b, "variable");
    }
    if (!anyStep) {
      throw loc(el, XProcException.xs(15, "A subpipeline must contain at least one step"));
    }
    // source / context
    if (withInput != null) {
      checkAttributes(withInput, Set.of("select", "href", "pipe", "exclude-inline-prefixes",
          "use-when", "expand-text"));
      Env ienv = outer.copy();
      ienv.expandText = expandTextOf(withInput, outer.expandText);
      c.source = parseConnection(withInput, ienv, ConnKind.WITH_INPUT, null);
      String select = withInput.getAttributeValue(SELECT);
      if (select != null) {
        c.source.select = compile(new XPathExpr(select, exprContext(withInput, outer.bindings,
            outer.functions, outer.types, outer.decl)));
        addVarRefs(c.source.select, outer);
      }
    } else if (c.kind == Container.Kind.FOR_EACH || c.kind == Container.Kind.VIEWPORT) {
      if (outer.drp == null) {
        throw loc(el, XProcException.xs(32, "No input for " + el.getNodeName()
            + " and no default readable port"));
      }
      c.source = new Connection(outer.drp);
      c.refs.add(outer.drp.producer());
    }
    if (c.kind == Container.Kind.IF || c.kind == Container.Kind.WHEN) {
      String test = el.getAttributeValue(new QName("test"));
      if (test == null) {
        throw loc(el, XProcException.xs(38, "Missing required attribute 'test'"));
      }
      c.collection = booleanAttr(el, "collection", false);
      c.test = compile(new XPathExpr(test, exprContext(el, outer.bindings, outer.functions,
          outer.types, outer.decl)));
      addVarRefs(c.test, outer);
      if (c.source == null && outer.drp != null && (c.test.usesContext || c.collection)) {
        c.refs.add(outer.drp.producer());
      }
    }
    if (c.kind == Container.Kind.IF && outer.drp != null) {
      // when the test is false the documents on the default readable port are copied
      c.refs.add(outer.drp.producer());
    }
    if (c.kind == Container.Kind.VIEWPORT) {
      String match = el.getAttributeValue(new QName("match"));
      if (match == null) {
        throw loc(el, XProcException.xs(38, "Missing required attribute 'match'"));
      }
      c.test = compile(new XPathExpr(match, exprContext(el, outer.bindings, outer.functions,
          outer.types, outer.decl), true));
      addVarRefs(c.test, outer);
    }
    if (c.kind == Container.Kind.FOR_EACH || c.kind == Container.Kind.VIEWPORT) {
      PortDecl current = new PortDecl("current", true);
      current.primary = true;
      c.innerInputs.put("current", current);
    }
    parseContainerBody(c, el, body, outputs, outer, defaultName);
    if (c.kind == Container.Kind.VIEWPORT && c.primaryOutput() == null) {
      throw loc(el, XProcException.xs(6, "The viewport has no primary output port and the last "
          + "step has no primary output port"));
    }
    if (c.kind == Container.Kind.IF && c.primaryOutput() == null) {
      throw loc(el, XProcException.xs(108, "p:if must have a primary output port"));
    }
  }

  /** Parses the subpipeline and outputs of a container. */
  private void parseContainerBody(Container c, XdmNode el, List<XdmNode> body,
      List<XdmNode> outputs, Env outer, String defaultName) {
    Env env = outer.copy();
    if (c.name != null) {
      env.readable.put(c.name, c);
    }
    env.ancestors.add(c);
    String primaryIn = null;
    for (PortDecl p : c.innerInputs.values()) {
      if (p.primary) {
        primaryIn = p.name;
      }
    }
    if (primaryIn != null) {
      env.drp = new Binding.Pipe(c, primaryIn);
    }
    env.current = c;
    parseSubpipeline(c, body, env, defaultName);
    // outputs
    Env outEnv = env.copy();
    for (Node n : c.children) {
      if (n instanceof StepNode s) {
        outEnv.readable.put(s.name, s);
      }
    }
    outEnv.drp = lastStepPort(c);
    Map<String, PortDecl> outs = new LinkedHashMap<>();
    int explicitPrimary = 0;
    for (XdmNode o : outputs) {
      checkAttributes(o, Set.of("port", "sequence", "primary", "content-types", "href", "pipe",
          "exclude-inline-prefixes", "use-when", "expand-text"));
      PortDecl p = parsePortCommon(o, false, false);
      if (outs.containsKey(p.name)) {
        throw loc(o, XProcException.xs(11, "Duplicate port name: " + p.name));
      }
      if (Boolean.TRUE.equals(p.primarySpecified)) {
        explicitPrimary++;
      }
      Env oenv = outEnv.copy();
      oenv.expandText = expandTextOf(o, outEnv.expandText);
      oenv.current = c;
      if (hasConnection(o)) {
        p.connection = parseConnection(o, oenv, ConnKind.COMPOUND_OUTPUT, c);
      }
      outs.put(p.name, p);
    }
    if (explicitPrimary > 1) {
      throw loc(el, XProcException.xs(14, "More than one primary output port"));
    }
    if (explicitPrimary == 0 && outs.size() == 1) {
      PortDecl p = outs.values().iterator().next();
      if (p.primarySpecified == null) {
        p.primary = true;
      }
    }
    for (PortDecl p : outs.values()) {
      if (p.connection == null) {
        if (p.primary) {
          p.connection = connectToLastStep(c, el);
        } else {
          p.connection = new Connection();
        }
      }
    }
    if (outputs.isEmpty() && c.kind != Container.Kind.FINALLY) {
      StepNode last = c.lastStep();
      String primary = last == null ? null : outsidePrimary(last);
      if (primary != null) {
        PortDecl p = new PortDecl("!result", false);
        p.primary = true;
        PortDecl lastPort = last instanceof AtomicStep a ? a.outputs.get(primary) : null;
        p.sequence = lastPort == null || lastPort.sequence;
        if (lastPort != null) {
          p.contentTypes = lastPort.contentTypes;
        }
        p.connection = new Connection(new Binding.Pipe(last, primary));
        c.refs.add(last);
        outs.put(p.name, p);
        c.implicitOutput = true;
      }
    }
    if (c.kind == Container.Kind.FINALLY) {
      StepNode lastFin = c.lastStep();
      if (outputs.isEmpty() && lastFin != null && outsidePrimary(lastFin) != null) {
        throw loc(el, XProcException.xs(112, "p:finally has an implicit primary output port"));
      }
      for (PortDecl p : outs.values()) {
        if (p.primary) {
          throw loc(el, XProcException.xs(112, "p:finally cannot have a primary output port"));
        }
      }
    }
    c.outputs.clear();
    c.outputs.putAll(outs);
  }

  private void parseChoose(Container c, XdmNode el, List<XdmNode> children, Env outer,
      String defaultName) {
    XdmNode withInput = null;
    List<XdmNode> branches = new ArrayList<>();
    boolean otherwise = false;
    for (XdmNode ch : children) {
      if (isP(ch, "with-input")) {
        if (!branches.isEmpty() || withInput != null) {
          throw loc(ch, XProcException.xs(100, "Misplaced p:with-input in p:choose"));
        }
        if (ch.getAttributeValue(PORT) != null) {
          throw loc(ch, XProcException.xs(43, "A port cannot be specified on p:with-input of "
              + "p:choose"));
        }
        withInput = ch;
      } else if (isP(ch, "when")) {
        if (otherwise) {
          throw loc(ch, XProcException.xs(100, "p:when cannot follow p:otherwise"));
        }
        branches.add(ch);
      } else if (isP(ch, "otherwise")) {
        if (otherwise) {
          throw loc(ch, XProcException.xs(100, "Only one p:otherwise is allowed"));
        }
        otherwise = true;
        branches.add(ch);
      } else {
        throw loc(ch, XProcException.xs(100, "Element " + ch.getNodeName()
            + " is not allowed in p:choose"));
      }
    }
    if (branches.isEmpty()) {
      throw loc(el, XProcException.xs(74, "p:choose must contain p:when or p:otherwise"));
    }
    if (withInput != null) {
      checkAttributes(withInput, Set.of("select", "href", "pipe", "exclude-inline-prefixes",
          "use-when", "expand-text"));
      Env ienv = outer.copy();
      ienv.expandText = expandTextOf(withInput, outer.expandText);
      c.source = parseConnection(withInput, ienv, ConnKind.CONTEXT, null);
      String select = withInput.getAttributeValue(SELECT);
      if (select != null) {
        c.source.select = compile(new XPathExpr(select, exprContext(withInput, outer.bindings,
            outer.functions, outer.types, outer.decl)));
        addVarRefs(c.source.select, outer);
      }
    }
    if (outer.drp != null) {
      c.refs.add(outer.drp.producer());
    }
    int pos = 0;
    Env benv = outer.copy();
    benv.current = c;
    if (c.name != null) {
      benv.readable.put(c.name, c);
    }
    benv.ancestors.add(c);
    for (XdmNode b : branches) {
      pos = positionOf(b, outer.bindings, outer.types, outer.decl);
      Container branch = new Container(b, isP(b, "when") ? Container.Kind.WHEN
          : Container.Kind.OTHERWISE);
      branch.parent = c;
      String bname = b.getAttributeValue(NAME);
      branch.name = bname != null ? bname : defaultName + "." + pos;
      branch.explicitName = bname != null;
      if (branch.explicitName && benv.readable.containsKey(bname)) {
        throw loc(b, XProcException.xs(2, "Duplicate step name: " + bname));
      }
      branch.type = b.getNodeName();
      checkAttributes(b, branch.kind == Container.Kind.WHEN
          ? Set.of("name", "test", "collection", "use-when", "expand-text")
          : Set.of("name", "use-when", "expand-text"));
      Env e = benv.copy();
      e.current = branch;
      e.expandText = expandTextOf(b, benv.expandText);
      branch.drp = e.drp;
      parseSimpleCompound(branch, b, elementChildren(b, e.bindings, e.types, e.decl), e,
          branch.name);
      c.branches.add(branch);
      c.refs.addAll(branch.refs);
    }
    c.refs.remove(c);
    mergeBranchOutputs(c, c.branches, el, !otherwise);
  }

  private void parseTry(Container c, XdmNode el, List<XdmNode> children, Env outer,
      String defaultName) {
    List<XdmNode> outputs = new ArrayList<>();
    List<XdmNode> body = new ArrayList<>();
    List<XdmNode> catches = new ArrayList<>();
    XdmNode fin = null;
    for (XdmNode ch : children) {
      if (isP(ch, "output")) {
        if (!body.isEmpty() || !catches.isEmpty() || fin != null) {
          throw loc(ch, XProcException.xs(100, "Misplaced p:output in p:try"));
        }
        outputs.add(ch);
      } else if (isP(ch, "catch")) {
        if (fin != null) {
          throw loc(ch, XProcException.xs(75, "p:catch cannot follow p:finally"));
        }
        catches.add(ch);
      } else if (isP(ch, "finally")) {
        if (fin != null) {
          throw loc(ch, XProcException.xs(75, "Only one p:finally is allowed"));
        }
        fin = ch;
      } else {
        if (!catches.isEmpty() || fin != null) {
          throw loc(ch, XProcException.xs(100, "Steps must precede p:catch and p:finally"));
        }
        body.add(ch);
      }
    }
    if (body.isEmpty() || (catches.isEmpty() && fin == null)) {
      throw loc(el, XProcException.xs(75, "p:try requires a subpipeline and at least one "
          + "p:catch or p:finally"));
    }
    Env benv = outer.copy();
    benv.current = c;
    if (c.name != null) {
      benv.readable.put(c.name, c);
    }
    benv.ancestors.add(c);
    Container initial = new Container(el, Container.Kind.GROUP);
    initial.parent = c;
    initial.name = "!" + c.name + "-try";
    initial.type = Ns.p("group");
    initial.drp = benv.drp;
    Env ienv = benv.copy();
    ienv.current = initial;
    parseContainerBody(initial, el, body, outputs, ienv, defaultName);
    c.branches.add(initial);
    c.refs.addAll(initial.refs);
    Set<QName> seenCodes = new HashSet<>();
    for (int i = 0; i < catches.size(); i++) {
      XdmNode k = catches.get(i);
      checkAttributes(k, Set.of("name", "code", "use-when", "expand-text"));
      Container katch = new Container(k, Container.Kind.CATCH);
      katch.parent = c;
      String kname = k.getAttributeValue(NAME);
      katch.name = kname != null ? kname : defaultName + "." + positionOf(k, outer.bindings,
          outer.types, outer.decl);
      katch.explicitName = kname != null;
      if (katch.explicitName && benv.readable.containsKey(kname)) {
        throw loc(k, XProcException.xs(2, "Duplicate step name: " + kname));
      }
      katch.type = k.getNodeName();
      String code = k.getAttributeValue(new QName("code"));
      if (code == null && i < catches.size() - 1) {
        throw loc(k, XProcException.xs(64, "Only the last p:catch may omit the code attribute"));
      }
      if (code != null) {
        if (code.isBlank()) {
          throw loc(k, XProcException.xs(83, "Invalid code attribute"));
        }
        for (String t : code.trim().split("\\s+")) {
          QName qn;
          try {
            qn = QNames.parseEQName(t, ExprContext.namespacesOf(k), "XS", 83, 83);
          } catch (XProcException e) {
            throw loc(k, e);
          }
          if (!seenCodes.add(qn)) {
            throw loc(k, XProcException.xs(64, "Error code " + t
                + " appears in more than one p:catch"));
          }
          katch.codes.add(qn);
        }
      }
      PortDecl error = new PortDecl("error", true);
      error.primary = true;
      error.sequence = true;
      katch.innerInputs.put("error", error);
      Env kenv = benv.copy();
      kenv.current = katch;
      kenv.expandText = expandTextOf(k, benv.expandText);
      katch.drp = kenv.drp;
      List<XdmNode> kch = elementChildren(k, kenv.bindings, kenv.types, kenv.decl);
      List<XdmNode> kout = new ArrayList<>();
      List<XdmNode> kbody = new ArrayList<>();
      for (XdmNode ch : kch) {
        if (isP(ch, "output") && kbody.isEmpty()) {
          kout.add(ch);
        } else {
          kbody.add(ch);
        }
      }
      parseContainerBody(katch, k, kbody, kout, kenv, katch.name);
      c.branches.add(katch);
      c.refs.addAll(katch.refs);
    }
    mergeBranchOutputs(c, c.branches, el, false);
    if (fin != null) {
      checkAttributes(fin, Set.of("name", "use-when", "expand-text"));
      Container f = new Container(fin, Container.Kind.FINALLY);
      f.parent = c;
      String fname = fin.getAttributeValue(NAME);
      f.name = fname != null ? fname : defaultName + "." + positionOf(fin, outer.bindings,
          outer.types, outer.decl);
      f.explicitName = fname != null;
      if (f.explicitName && benv.readable.containsKey(fname)) {
        throw loc(fin, XProcException.xs(2, "Duplicate step name: " + fname));
      }
      f.type = fin.getNodeName();
      PortDecl error = new PortDecl("error", true);
      error.primary = true;
      error.sequence = true;
      f.innerInputs.put("error", error);
      Env fenv = benv.copy();
      fenv.current = f;
      fenv.expandText = expandTextOf(fin, benv.expandText);
      f.drp = fenv.drp;
      List<XdmNode> fch = elementChildren(fin, fenv.bindings, fenv.types, fenv.decl);
      List<XdmNode> fout = new ArrayList<>();
      List<XdmNode> fbody = new ArrayList<>();
      for (XdmNode ch : fch) {
        if (isP(ch, "output") && fbody.isEmpty()) {
          fout.add(ch);
        } else {
          fbody.add(ch);
        }
      }
      parseContainerBody(f, fin, fbody, fout, fenv, f.name);
      for (String port : f.outputs.keySet()) {
        if (c.outputs.containsKey(port)) {
          throw loc(fin, XProcException.xs(72, "Output port " + port
              + " of p:finally conflicts with another output of the p:try"));
        }
      }
      for (PortDecl p : f.outputs.values()) {
        PortDecl q = p.copy(p.name);
        q.sequence = true;
        q.primary = false;
        c.outputs.put(p.name, q);
      }
      c.finallyBranch = f;
      c.refs.addAll(f.refs);
    }
    c.refs.remove(c);
  }

  /** Computes the outputs of choose/try as the union of the branch outputs. */
  private void mergeBranchOutputs(Container c, List<Container> branches, XdmNode el,
      boolean missingOtherwise) {
    String primary = null;
    boolean first = true;
    for (Container b : branches) {
      String bp = b.primaryOutput();
      if (first) {
        primary = bp;
        first = false;
      } else if (primary == null ? bp != null : !primary.equals(bp)) {
        throw loc(el, XProcException.xs(102,
            "Alternative subpipelines have different primary output ports"));
      }
    }
    Map<String, PortDecl> outs = new LinkedHashMap<>();
    for (Container b : branches) {
      for (PortDecl p : b.outputs.values()) {
        if (!outs.containsKey(p.name)) {
          PortDecl q = p.copy(p.name);
          q.sequence = true;
          q.contentTypes = MediaType.parseContentTypes(null);
          q.primary = p.name.equals(primary);
          q.connection = null;
          outs.put(p.name, q);
        }
      }
    }
    c.outputs.clear();
    c.outputs.putAll(outs);
  }

  // -----------------------------------------------------------------------------------------
  // Connections

  private static boolean hasImplicitInline(XdmNode el) {
    for (XdmNode ch : el.children()) {
      if (ch.getNodeKind() == XdmNodeKind.ELEMENT
          && !Ns.XPROC.equals(ch.getNodeName().getNamespace())) {
        return true;
      }
    }
    return false;
  }

  private boolean hasConnection(XdmNode el) {
    if (el.getAttributeValue(HREF) != null || el.getAttributeValue(PIPE) != null) {
      return true;
    }
    for (XdmNode ch : el.children()) {
      if (ch.getNodeKind() == XdmNodeKind.ELEMENT && !isDocumentation(ch)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Parses the connection of p:input, p:with-input, p:output, p:variable, p:with-option.
   *
   * @param outputOwner for compound outputs: the compound step
   */
  private Connection parseConnection(XdmNode el, Env env, ConnKind kind,
      Container outputOwner) {
    Connection conn = new Connection();
    conn.location = Location.of(el);
    String href = el.getAttributeValue(HREF);
    String pipe = el.getAttributeValue(PIPE);
    List<XdmNode> children = new ArrayList<>();
    for (XdmNode ch : el.children()) {
      if (ch.getNodeKind() == XdmNodeKind.ELEMENT) {
        if (isDocumentation(ch) || !included(ch, env.bindings, env.types, env.decl)) {
          continue;
        }
        children.add(ch);
      }
    }
    if (href != null && pipe != null) {
      throw loc(el, XProcException.xs(85, "Both href and pipe are specified"));
    }
    if (href != null) {
      if (!children.isEmpty()) {
        throw loc(el, XProcException.xs(81, "href cannot be combined with child elements"));
      }
      ExprContext ctx = exprContext(el, env.bindings, env.functions, env.types, env.decl);
      ValueTemplate t = parseAvt(el, href, ctx, env);
      Binding.Pipe ctxPort = t.usesContext() ? env.drp : null;
      if (ctxPort != null) {
        env.current.refs.add(ctxPort.producer());
      }
      conn.bindings.add(new Binding.Document(t, null, null, null, ctxPort, ctx));
      return conn;
    }
    if (pipe != null) {
      if (kind == ConnKind.INPUT_DECL) {
        throw loc(el, XProcException.xs(100, "p:input cannot have a pipe connection"));
      }
      if (!children.isEmpty()) {
        throw loc(el, XProcException.xs(82, "pipe cannot be combined with child elements"));
      }
      String p = pipe.trim();
      if (p.isEmpty()) {
        conn.bindings.add(resolvePipe(el, null, null, env, kind, outputOwner));
        return conn;
      }
      for (String token : p.split("\\s+")) {
        String port;
        String step;
        int at = token.indexOf('@');
        if (at < 0) {
          port = token;
          step = null;
        } else {
          port = at == 0 ? null : token.substring(0, at);
          step = token.substring(at + 1);
        }
        if ((port != null && !QNames.isNCName(port)) || (at >= 0 && !QNames.isNCName(step))) {
          throw loc(el, XProcException.xs(90, "Invalid pipe attribute value: " + pipe));
        }
        conn.bindings.add(resolvePipe(el, step, port, env, kind, outputOwner));
      }
      return conn;
    }
    boolean empty = false;
    boolean implicitSeen = false;
    boolean explicitSeen = false;
    for (XdmNode ch : children) {
      QName n = ch.getNodeName();
      if (Ns.XPROC.equals(n.getNamespace())) {
        explicitSeen = true;
        switch (n.getLocalName()) {
          case "empty" -> {
            checkAttributes(ch, Set.of("use-when", "expand-text"));
            empty = true;
            conn.bindings.add(new Binding.Empty());
          }
          case "pipe" -> {
            if (kind == ConnKind.INPUT_DECL) {
              throw loc(ch, XProcException.xs(100, "p:input cannot contain p:pipe"));
            }
            checkAttributes(ch, Set.of("step", "port", "use-when", "expand-text"));
            String step = ch.getAttributeValue(STEP);
            String port = ch.getAttributeValue(PORT);
            if ((step != null && !QNames.isNCName(step))
                || (port != null && !QNames.isNCName(port))) {
              throw loc(ch, XProcException.xs(99, "Invalid step or port name on p:pipe"));
            }
            conn.bindings.add(resolvePipe(ch, step, port, env, kind, outputOwner));
          }
          case "document" -> conn.bindings.add(parseDocument(ch, env));
          case "inline" -> conn.bindings.add(parseInline(ch, false, env, el));
          default -> throw loc(ch, XProcException.xs(100, "Element p:" + n.getLocalName()
              + " is not allowed here"));
        }
      } else {
        implicitSeen = true;
        conn.bindings.add(parseInline(ch, true, env, el));
      }
    }
    if (empty && conn.bindings.size() > 1) {
      throw loc(el, XProcException.xs(89, "p:empty cannot be combined with other bindings"));
    }
    if (implicitSeen && explicitSeen) {
      throw loc(el, XProcException.xs(100, "Implicit inlines cannot be combined with other "
          + "bindings"));
    }
    if (implicitSeen) {
      for (XdmNode ch : el.children()) {
        if (ch.getNodeKind() == XdmNodeKind.COMMENT
            || ch.getNodeKind() == XdmNodeKind.PROCESSING_INSTRUCTION
            || (ch.getNodeKind() == XdmNodeKind.TEXT && !ch.getStringValue().isBlank())) {
          throw loc(el, XProcException.xs(79, "Comments, processing instructions or text "
              + "cannot be siblings of an implicit inline"));
        }
      }
    } else {
      checkNoText(el);
    }
    if (conn.bindings.isEmpty() && kind == ConnKind.WITH_INPUT && !explicitSeen) {
      // <p:with-input/> means: the default readable port
      if (env.drp == null) {
        throw loc(el, XProcException.xs(32, "No default readable port available for "
            + "p:with-input"));
      }
      conn.bindings.add(env.drp);
      env.current.refs.add(env.drp.producer());
    }
    return conn;
  }

  private Binding.Pipe resolvePipe(XdmNode el, String stepName, String port, Env env,
      ConnKind kind, Container outputOwner) {
    StepNode producer;
    boolean ancestor;
    if (stepName == null) {
      if (env.drp == null) {
        throw loc(el, XProcException.xs(67, "p:pipe without step attribute and no default "
            + "readable port"));
      }
      if (port == null) {
        env.current.refs.add(env.drp.producer());
        return env.drp;
      }
      producer = (StepNode) env.drp.producer();
      ancestor = env.ancestors.contains(producer);
    } else {
      producer = env.readable.get(stepName);
      if (producer == null) {
        int code = kind == ConnKind.COMPOUND_OUTPUT ? 78 : 22;
        throw loc(el, XProcException.xs(code, "No readable step named '" + stepName + "'"));
      }
      ancestor = env.ancestors.contains(producer);
    }
    if (ancestor) {
      Container c = (Container) producer;
      if (port == null) {
        for (PortDecl p : c.innerInputs.values()) {
          if (p.primary) {
            port = p.name;
          }
        }
        if (port == null) {
          throw loc(el, XProcException.xs(68, "Step '" + producer.name
              + "' has no primary input port to read from"));
        }
      }
      if (!c.innerInputs.containsKey(port)) {
        int code = kind == ConnKind.COMPOUND_OUTPUT ? 78 : 22;
        throw loc(el, XProcException.xs(code, "Port '" + port + "' on step '" + producer.name
            + "' is not readable"));
      }
      return new Binding.Pipe(c, port);
    }
    if (port == null) {
      port = outsidePrimary(producer);
      if (port == null) {
        throw loc(el, XProcException.xs(68, "Step '" + producer.name
            + "' has no primary output port"));
      }
    }
    if (!hasOutsidePort(producer, port)) {
      int code = kind == ConnKind.COMPOUND_OUTPUT ? 78 : 22;
      throw loc(el, XProcException.xs(code, "Step '" + producer.name
          + "' has no output port named '" + port + "'"));
    }
    if (env.current != null) {
      env.current.refs.add(producer);
    }
    return new Binding.Pipe(producer, port);
  }

  private Binding parseDocument(XdmNode el, Env env) {
    checkAttributes(el, Set.of("href", "content-type", "document-properties", "parameters",
        "use-when", "expand-text"));
    checkNoText(el);
    for (XdmNode ch : el.children()) {
      if (ch.getNodeKind() == XdmNodeKind.ELEMENT && !isDocumentation(ch)) {
        throw loc(ch, XProcException.xs(100, "p:document must be empty"));
      }
    }
    String href = requiredAttr(el, "href");
    ExprContext ctx = exprContext(el, env.bindings, env.functions, env.types, env.decl);
    ValueTemplate t = parseAvt(el, href, ctx, env);
    String ct = el.getAttributeValue(new QName("content-type"));
    if (ct != null && MediaType.tryParse(ct) == null) {
      throw loc(el, XProcException.xd(79, "Invalid content type: " + ct));
    }
    XPathExpr props = null;
    String dp = el.getAttributeValue(new QName("document-properties"));
    if (dp != null) {
      props = compile(new XPathExpr(dp, ctx));
      addVarRefs(props, env);
    }
    XPathExpr params = null;
    String pa = el.getAttributeValue(new QName("parameters"));
    if (pa != null) {
      params = compile(new XPathExpr(pa, ctx));
      addVarRefs(params, env);
    }
    boolean usesContext = t.usesContext() || (props != null && props.usesContext)
        || (params != null && params.usesContext);
    Binding.Pipe ctxPort = usesContext ? env.drp : null;
    if (ctxPort != null && env.current != null) {
      env.current.refs.add(ctxPort.producer());
    }
    return new Binding.Document(t, ct, props, params, ctxPort, ctx);
  }

  private Binding parseInline(XdmNode el, boolean implicit, Env env, XdmNode connElement) {
    String contentType = null;
    String encoding = null;
    XPathExpr props = null;
    ExprContext ctx;
    boolean expand = env.expandText;
    Set<String> excluded = new HashSet<>(env.excludedNs);
    excluded.add(Ns.XPROC);
    excluded.addAll(excludedNamespaces(connElement));
    if (!implicit) {
      checkAttributes(el, Set.of("exclude-inline-prefixes", "content-type",
          "document-properties", "encoding", "use-when", "expand-text"));
      excluded.addAll(excludedNamespaces(el));
      expand = expandTextOf(el, expand);
      contentType = el.getAttributeValue(new QName("content-type"));
      if (contentType != null && MediaType.tryParse(contentType) == null) {
        throw loc(el, XProcException.xd(79, "Invalid content type: " + contentType));
      }
      encoding = el.getAttributeValue(new QName("encoding"));
      if (encoding != null && !"base64".equals(encoding)) {
        throw loc(el, XProcException.xs(69, "Unsupported encoding: " + encoding));
      }
      ctx = exprContext(el, env.bindings, env.functions, env.types, env.decl);
      String dp = el.getAttributeValue(new QName("document-properties"));
      if (dp != null) {
        props = compile(new XPathExpr(dp, ctx));
        addVarRefs(props, env);
      }
    } else {
      if (Ns.XPROC.equals(el.getNodeName().getNamespace())) {
        throw loc(el, XProcException.xs(100, "Implicit inlines cannot be in the XProc namespace"));
      }
      ctx = exprContext(el, env.bindings, env.functions, env.types, env.decl);
    }
    MediaType mt = contentType == null ? MediaType.XML : MediaType.parse(contentType);
    Map<XdmNode, ValueTemplate> templates = new HashMap<>();
    Set<XdmNode> dropped = new HashSet<>();
    Set<XdmNode> excludedEls = new HashSet<>();
    if (implicit) {
      for (XdmNode a : el.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
        if (a.getNodeName().equals(P_USE_WHEN) || a.getNodeName().equals(P_EXPAND_TEXT)) {
          dropped.add(a);
        }
      }
      expand = expandTextOf(el, expand);
    }
    if (encoding == null) {
      if (implicit) {
        collectTemplates(el, expand, mt.isMarkup(), templates, dropped, excludedEls, env);
      } else {
        for (XdmNode ch : el.children()) {
          collectTemplates(ch, expand, mt.isMarkup(), templates, dropped, excludedEls, env);
        }
      }
    }
    boolean usesContext = props != null && props.usesContext;
    for (ValueTemplate t : templates.values()) {
      usesContext |= t.usesContext();
    }
    Binding.Pipe ctxPort = usesContext ? env.drp : null;
    if (ctxPort != null && env.current != null) {
      env.current.refs.add(ctxPort.producer());
    }
    return new Binding.Inline(el, implicit, contentType, encoding, props, excluded,
        new Binding.InlineTemplates(templates, dropped, excludedEls), ctxPort, ctx);
  }

  private void collectTemplates(XdmNode node, boolean expand, boolean markup,
      Map<XdmNode, ValueTemplate> templates, Set<XdmNode> dropped, Set<XdmNode> excluded,
      Env env) {
    switch (node.getNodeKind()) {
      case ELEMENT -> {
        boolean xprocEl = Ns.XPROC.equals(node.getNodeName().getNamespace());
        QName uw = xprocEl ? USE_WHEN : P_USE_WHEN;
        if (node.getAttributeValue(uw) != null) {
          if (!included(node, env.bindings, env.types, env.decl)) {
            excluded.add(node);
            return;
          }
          for (XdmNode a : node.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
            if (a.getNodeName().equals(uw)) {
              dropped.add(a);
            }
          }
        }
        boolean e = expand;
        QName iet = xprocEl ? INLINE_EXPAND_TEXT : P_INLINE_EXPAND_TEXT;
        XdmNode ietAttr = null;
        for (XdmNode a : node.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
          if (a.getNodeName().equals(iet)) {
            ietAttr = a;
          }
        }
        if (ietAttr != null) {
          e = parseBoolean(node, iet.getLocalName(), ietAttr.getStringValue(), 113);
          dropped.add(ietAttr);
        }
        for (XdmNode a : node.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
          if (a == ietAttr || a.equals(ietAttr)) {
            continue;
          }
          if (e && markup && hasBraces(a.getStringValue())) {
            if (a.getNodeName().equals(Ns.xml("base"))) {
              continue;
            }
            ExprContext ctx = exprContext(node, env.bindings, env.functions, env.types,
                env.decl);
            templates.put(a, parseAvt(node, a.getStringValue(), ctx, env));
          }
        }
        for (XdmNode ch : node.children()) {
          collectTemplates(ch, e, markup, templates, dropped, excluded, env);
        }
      }
      case TEXT -> {
        if (expand && hasBraces(node.getStringValue())) {
          XdmNode parent = node.getParent();
          ExprContext ctx = exprContext(parent, env.bindings, env.functions, env.types,
              env.decl);
          templates.put(node, parseAvt(parent, node.getStringValue(), ctx, env));
        }
      }
      default -> {
      }
    }
  }

  private static boolean hasBraces(String s) {
    return s.indexOf('{') >= 0 || s.indexOf('}') >= 0;
  }

  // =========================================================================================
  // Expressions

  private ExprContext exprContext(XdmNode el, Map<QName, Binder> bindings,
      List<XsltPackage> functions, TypeScope scope, StepDeclaration decl) {
    return exprContext(el, bindings, functions, scope, decl, false);
  }

  private ExprContext exprContext(XdmNode el, Map<QName, Binder> bindings,
      List<XsltPackage> functions, TypeScope scope, StepDeclaration decl, boolean useWhen) {
    Map<String, String> ns = ExprContext.namespacesOf(el);
    if (standardMode) {
      ns.putIfAbsent("xs", Ns.XS);
    }
    ExprContext ctx = new ExprContext(ns, baseUri(el), Location.of(el), bindings, functions);
    if (scope != null) {
      ctx.stepAvailable = q -> stepAvailable(scope, q, decl, useWhen);
    }
    return ctx;
  }

  private boolean stepAvailable(TypeScope scope, QName type, StepDeclaration current,
      boolean useWhen) {
    StepDeclaration d = scope.lookup(type);
    if (d == null) {
      for (TypeScope ts = scope; ts != null; ts = ts.parent) {
        java.util.function.BooleanSupplier f = ts.forward.get(type);
        if (f != null) {
          return f.getAsBoolean();
        }
      }
      return false;
    }
    if (useWhen && (d.underConstruction || d == current)) {
      return false;
    }
    if (d.isExternal()) {
      return pc.steps.isAvailable(type);
    }
    return true;
  }

  private XPathExpr compile(XPathExpr x) {
    pc.xpath.compile(x);
    return x;
  }

  private void addVarRefs(XPathExpr x, Env env) {
    if (env.current == null) {
      return;
    }
    for (QName v : x.variableRefs) {
      Binder b = x.context.bindings.get(v);
      if (b instanceof VariableNode) {
        env.current.refs.add(b);
      }
    }
  }

  private ValueTemplate parseAvt(XdmNode el, String value, ExprContext ctx, Env env) {
    ValueTemplate t;
    try {
      t = ValueTemplate.parse(value, ctx);
    } catch (XProcException e) {
      throw loc(el, e);
    }
    for (XPathExpr x : t.expressions()) {
      compile(x);
      if (env != null) {
        addVarRefs(x, env);
      }
    }
    return t;
  }

  // =========================================================================================
  // Conditional element exclusion and children

  /** True if the element is not excluded by a use-when expression. */
  private boolean included(XdmNode el, Map<QName, Binder> bindings, TypeScope scope,
      StepDeclaration decl) {
    boolean xproc = Ns.XPROC.equals(el.getNodeName().getNamespace());
    String expr = el.getAttributeValue(xproc ? USE_WHEN : P_USE_WHEN);
    if (expr == null) {
      return true;
    }
    Boolean cached = includedCache.get(el);
    if (cached != null) {
      return cached;
    }
    if (!evaluatingUseWhen.add(el)) {
      throw loc(el, XProcException.xs(115, "Deadlock between use-when expressions"));
    }
    try {
      boolean result = evaluateUseWhen(el, expr, xproc, bindings, scope, decl);
      includedCache.put(el, result);
      return result;
    } finally {
      evaluatingUseWhen.remove(el);
    }
  }

  private boolean evaluateUseWhen(XdmNode el, String expr, boolean xproc,
      Map<QName, Binder> bindings, TypeScope scope, StepDeclaration decl) {
    if (xproc && el.getAttributeValue(P_USE_WHEN) != null) {
      throw loc(el, XProcException.xs(97, "p:use-when is not allowed on XProc elements"));
    }
    Map<QName, Binder> statics = new LinkedHashMap<>();
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    for (Map.Entry<QName, Binder> e : bindings.entrySet()) {
      if (e.getValue().isStatic()) {
        statics.put(e.getKey(), e.getValue());
        vars.put(e.getKey(), e.getValue().getStaticValue());
      }
    }
    ExprContext ctx = exprContext(el, statics, List.of(), scope, decl, true);
    if (useWhenPredicate != null) {
      ctx.stepAvailable = useWhenPredicate;
    }
    XPathExpr x = compile(new XPathExpr(expr, ctx));
    XdmValue v;
    try {
      v = pc.xpath.evaluate(x, null, ContextState.UNDEFINED, vars, null, null, true);
    } catch (XProcException e) {
      throw loc(el, e);
    }
    Map<QName, XdmValue> ebvVars = new LinkedHashMap<>();
    ebvVars.put(new QName("v"), v);
    XdmValue b = pc.xpath.evaluateForStep("boolean($v)", Map.of(), null, null, ebvVars);
    return nl.armatiek.xproc.internal.util.Xdm.bool(b);
  }

  /**
   * Resolves the use-when expressions of sibling p:declare-step elements in an order
   * independent way (err:XS0115 for deadlocks).
   */
  private void resolveDeclarationUseWhen(XdmNode parent, Map<QName, Binder> statics,
      TypeScope scope, StepDeclaration current) {
    Map<QName, XdmNode> byType = new HashMap<>();
    List<XdmNode> unresolved = new ArrayList<>();
    Map<XdmNode, Boolean> state = new HashMap<>();
    for (XdmNode ch : parent.children()) {
      if (ch.getNodeKind() != XdmNodeKind.ELEMENT || !isP(ch, "declare-step")) {
        continue;
      }
      String type = ch.getAttributeValue(TYPE);
      if (type != null) {
        try {
          byType.put(parseQName(ch, type, 77), ch);
        } catch (XProcException e) {
          // reported later
        }
      }
      if (ch.getAttributeValue(USE_WHEN) == null || includedCache.containsKey(ch)) {
        state.put(ch, includedCache.getOrDefault(ch, true));
      } else {
        unresolved.add(ch);
      }
    }
    if (unresolved.isEmpty()) {
      return;
    }
    boolean progress = true;
    while (progress && !unresolved.isEmpty()) {
      progress = false;
      for (XdmNode el : new ArrayList<>(unresolved)) {
        useWhenPredicate = q -> {
          XdmNode sib = byType.get(q);
          if (sib == null) {
            return stepAvailable(scope, q, current, true);
          }
          if (sib.equals(el)) {
            return false;
          }
          Boolean st = state.get(sib);
          if (st == null) {
            throw new Unresolved();
          }
          return st && hasSubpipeline(sib);
        };
        try {
          boolean r = evaluateUseWhen(el, el.getAttributeValue(USE_WHEN), true, statics, scope,
              current);
          state.put(el, r);
          includedCache.put(el, r);
          unresolved.remove(el);
          progress = true;
        } catch (Unresolved u) {
          // try again later
        } finally {
          useWhenPredicate = null;
        }
      }
    }
    if (!unresolved.isEmpty()) {
      throw loc(unresolved.get(0), XProcException.xs(115,
          "Deadlock between use-when expressions of step declarations"));
    }
  }

  private void registerForward(XdmNode parent, TypeScope scope, Map<QName, Binder> statics,
      StepDeclaration current) {
    for (XdmNode ch : parent.children()) {
      if (ch.getNodeKind() != XdmNodeKind.ELEMENT || !isP(ch, "declare-step")) {
        continue;
      }
      String type = ch.getAttributeValue(TYPE);
      if (type == null) {
        continue;
      }
      QName qn;
      try {
        qn = parseQName(ch, type, 77);
      } catch (XProcException e) {
        continue;
      }
      scope.forward.put(qn, () -> included(ch, statics, scope, current) && hasSubpipeline(ch));
    }
  }

  private boolean hasSubpipeline(XdmNode decl) {
    for (XdmNode ch : decl.children()) {
      if (ch.getNodeKind() == XdmNodeKind.ELEMENT && isStepElement(ch)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The element children of an element, without documentation/pipeinfo and effectively
   * excluded elements. Raises err:XS0037 for non-whitespace text.
   */
  private List<XdmNode> elementChildren(XdmNode el, Map<QName, Binder> bindings,
      TypeScope scope, StepDeclaration decl) {
    checkNoText(el);
    List<XdmNode> list = new ArrayList<>();
    for (XdmNode ch : el.children()) {
      if (ch.getNodeKind() != XdmNodeKind.ELEMENT || isDocumentation(ch)) {
        continue;
      }
      if (included(ch, bindings, scope, decl)) {
        list.add(ch);
      }
    }
    return list;
  }

  /** Position (1-based) of an element among its non-excluded element siblings. */
  private int positionOf(XdmNode el, Map<QName, Binder> bindings, TypeScope scope,
      StepDeclaration decl) {
    int pos = 0;
    XdmNode parent = el.getParent();
    for (XdmNode ch : parent.children()) {
      if (ch.getNodeKind() != XdmNodeKind.ELEMENT) {
        continue;
      }
      if (ch.equals(el)) {
        return pos + 1;
      }
      if (isDocumentation(ch) || included(ch, bindings, scope, decl)) {
        pos++;
      }
    }
    return pos + 1;
  }

  private void checkNoText(XdmNode el) {
    for (XdmNode ch : el.children()) {
      if (ch.getNodeKind() == XdmNodeKind.TEXT && !ch.getStringValue().isBlank()) {
        throw loc(el, XProcException.xs(37, "Element " + el.getNodeName()
            + " cannot contain text"));
      }
    }
  }

  // =========================================================================================
  // Attribute helpers

  private void checkAttributes(XdmNode el, Set<String> allowed) {
    for (XdmNode attr : el.axisIterator(Axis.ATTRIBUTE).stream().toList()) {
      QName n = attr.getNodeName();
      if (n.getNamespace().isEmpty()) {
        if (!allowed.contains(n.getLocalName())) {
          throw loc(el, XProcException.xs(8, "Attribute '" + n.getLocalName()
              + "' is not allowed on " + el.getNodeName()));
        }
      } else if (Ns.XPROC.equals(n.getNamespace())) {
        throw loc(el, XProcException.xs(97, "Attribute " + n + " is not allowed on "
            + el.getNodeName()));
      }
    }
    String expand = el.getAttributeValue(EXPAND_TEXT);
    if (expand != null && !expand.equals("true") && !expand.equals("false")) {
      throw loc(el, XProcException.xs(113, "Invalid expand-text value: " + expand));
    }
  }

  private boolean expandTextOf(XdmNode el, boolean inherited) {
    boolean xproc = Ns.XPROC.equals(el.getNodeName().getNamespace());
    String v = el.getAttributeValue(xproc ? EXPAND_TEXT : P_EXPAND_TEXT);
    if (v == null) {
      return inherited;
    }
    return parseBoolean(el, "expand-text", v, 113);
  }

  private Set<String> excludedNamespaces(XdmNode el) {
    Set<String> set = new HashSet<>();
    if (el == null) {
      return set;
    }
    String v = el.getAttributeValue(EXCLUDE_INLINE_PREFIXES);
    if (v == null) {
      return set;
    }
    Map<String, String> ns = ExprContext.namespacesOf(el);
    for (String t : v.trim().split("\\s+")) {
      if (t.isEmpty()) {
        continue;
      }
      if ("#all".equals(t)) {
        for (Map.Entry<String, String> e : ns.entrySet()) {
          if (!"xml".equals(e.getKey())) {
            set.add(e.getValue());
          }
        }
      } else if ("#default".equals(t)) {
        String d = ns.get("");
        if (d == null || d.isEmpty()) {
          throw loc(el, XProcException.xs(58, "#default used but there is no default namespace"));
        }
        set.add(d);
      } else {
        String u = ns.get(t);
        if (u == null || !QNames.isNCName(t)) {
          throw loc(el, XProcException.xs(57, "Prefix '" + t + "' is not bound"));
        }
        set.add(u);
      }
    }
    return set;
  }

  private String requiredAttr(XdmNode el, String name) {
    String v = el.getAttributeValue(new QName(name));
    if (v == null) {
      throw loc(el, XProcException.xs(38, "Missing required attribute '" + name + "' on "
          + el.getNodeName()));
    }
    return v;
  }

  private boolean booleanAttr(XdmNode el, String name, boolean dflt) {
    String v = el.getAttributeValue(new QName(name));
    if (v == null) {
      return dflt;
    }
    return parseBoolean(el, name, v);
  }

  private boolean parseBoolean(XdmNode el, String name, String v) {
    return parseBoolean(el, name, v, 77);
  }

  private boolean parseBoolean(XdmNode el, String name, String v, int code) {
    return switch (v.trim()) {
      case "true", "1" -> true;
      case "false", "0" -> false;
      default -> throw loc(el, XProcException.xs(code, "Invalid boolean value for " + name
          + ": " + v));
    };
  }

  private QName parseQName(XdmNode el, String value, int code) {
    try {
      return QNames.parseEQName(value, ExprContext.namespacesOf(el), "XS", code, code);
    } catch (XProcException e) {
      throw loc(el, e);
    }
  }

  private QName parseVarName(XdmNode el, String value) {
    QName name;
    try {
      name = QNames.parseEQName(value, ExprContext.namespacesOf(el), "XS", 77, 87);
    } catch (XProcException e) {
      throw loc(el, e);
    }
    if (Ns.XPROC.equals(name.getNamespace())) {
      throw loc(el, XProcException.xs(28, "Options and variables cannot be in the XProc "
          + "namespace"));
    }
    return name;
  }

  private void checkVersion(XdmNode el, boolean required) {
    String v = el.getAttributeValue(VERSION);
    if (v == null) {
      if (required) {
        throw loc(el, XProcException.xs(62, "The version attribute is required"));
      }
      return;
    }
    BigDecimal d;
    try {
      d = new BigDecimal(v.trim());
    } catch (NumberFormatException e) {
      throw loc(el, XProcException.xs(63, "The version attribute must be a decimal: " + v));
    }
    if (!(d.compareTo(new BigDecimal("3.0")) == 0 || d.compareTo(new BigDecimal("3.1")) == 0)) {
      throw loc(el, XProcException.xs(60, "Unsupported XProc version: " + v));
    }
  }

  private void checkXPathVersion(XdmNode el, String v) {
    BigDecimal d;
    try {
      d = new BigDecimal(v.trim());
    } catch (NumberFormatException e) {
      throw loc(el, XProcException.xs(77, "Invalid xpath-version: " + v));
    }
    if (d.compareTo(new BigDecimal("3.1")) != 0) {
      throw loc(el, XProcException.xs(110, "Unsupported XPath version: " + v));
    }
  }

  // =========================================================================================
  // Misc

  private static boolean isP(XdmNode el, String local) {
    QName n = el.getNodeName();
    return n != null && Ns.XPROC.equals(n.getNamespace()) && local.equals(n.getLocalName());
  }

  private static boolean isDocumentation(XdmNode el) {
    return isP(el, "documentation") || isP(el, "pipeinfo");
  }

  private static XdmNode firstElement(XdmNode doc) {
    for (XdmNode ch : doc.children()) {
      if (ch.getNodeKind() == XdmNodeKind.ELEMENT) {
        return ch;
      }
    }
    return null;
  }

  private static URI baseUri(XdmNode el) {
    if (el == null) {
      return null;
    }
    try {
      URI u = el.getBaseURI();
      return u == null || u.toString().isEmpty() ? null : u;
    } catch (IllegalStateException e) {
      return null;
    }
  }

  private static String canonical(URI uri) {
    return uri.normalize().toString();
  }

  private static XProcException loc(XdmNode el, XProcException e) {
    if (el == null) {
      return e;
    }
    return Location.of(el).apply(e);
  }
}
