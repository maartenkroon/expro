package nl.armatiek.xproc.internal.xpath;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.Type;
import net.sf.saxon.value.AnyURIValue;
import net.sf.saxon.value.BooleanValue;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.QNameValue;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.util.QNames;

/** The XPath extension functions defined by XProc 3.1 (section 8). */
public final class XProcFunctions {

  private XProcFunctions() {
  }

  public static void register(ProcessorContext pc) {
    var p = pc.processor;
    p.registerExtensionFunction(new Fn("system-property", 1, 1, SequenceType.SINGLE_STRING,
        args -> new StringValue(systemProperty(pc, str(args[0])))));
    p.registerExtensionFunction(new Fn("step-available", 1, 1, SequenceType.SINGLE_BOOLEAN,
        args -> BooleanValue.get(stepAvailable(str(args[0])))));
    p.registerExtensionFunction(new Fn("iteration-position", 0, 0, SequenceType.SINGLE_INTEGER,
        args -> Int64Value.makeIntegerValue(state().iterationPosition)));
    p.registerExtensionFunction(new Fn("iteration-size", 0, 0, SequenceType.SINGLE_INTEGER,
        args -> Int64Value.makeIntegerValue(state().iterationSize)));
    p.registerExtensionFunction(new Fn("version-available", 1, 1, SequenceType.SINGLE_BOOLEAN,
        args -> {
          state();
          String v = str(args[0]).trim();
          return BooleanValue.get("3.1".equals(v) || "3.0".equals(v));
        }));
    p.registerExtensionFunction(new Fn("xpath-version-available", 1, 1,
        SequenceType.SINGLE_BOOLEAN, args -> {
          state();
          String v = str(args[0]).trim();
          return BooleanValue.get("3.1".equals(v));
        }));
    p.registerExtensionFunction(new Fn("document-properties", 1, 1, SequenceType.ANY_SEQUENCE,
        args -> {
          state();
          Item item = args[0].head();
          return documentProperties(pc, item).getUnderlyingValue();
        }));
    p.registerExtensionFunction(new Fn("document-property", 2, 2, SequenceType.ANY_SEQUENCE,
        args -> {
          state();
          Item item = args[0].head();
          Item key = args[1].head();
          QName qkey;
          if (key instanceof QNameValue qv) {
            qkey = new QName(qv.getStructuredQName());
          } else if (key instanceof StringValue sv) {
            qkey = QNames.parseEQName(sv.getStringValue(), state().context.namespaces, "XD", 61,
                61);
          } else {
            return XdmEmptySequence.getInstance().getUnderlyingValue();
          }
          XdmMap props = documentProperties(pc, item);
          XdmValue v = props.get(new XdmAtomicValue(qkey));
          return v == null ? XdmEmptySequence.getInstance().getUnderlyingValue()
              : v.getUnderlyingValue();
        }));
    p.registerExtensionFunction(new Fn("urify", 1, 2, SequenceType.SINGLE_STRING, args -> {
      state();
      String base = args.length > 1 && args[1].head() != null ? str(args[1]) : null;
      return new StringValue(pc.urify.urify(str(args[0]), base));
    }));
    p.registerExtensionFunction(new Fn("function-library-importable", 1, 1,
        SequenceType.SINGLE_BOOLEAN, args -> {
          state();
          String ct = str(args[0]).trim();
          return BooleanValue.get(ct.equals("application/xslt+xml")
              || ct.equals("text/xsl") || ct.equals("application/xml+xslt"));
        }));
    p.registerExtensionFunction(new Fn("lookup-uri", 1, 1, SequenceType.SINGLE_ANY_URI,
        args -> {
          EvalState st = state();
          String href = str(args[0]);
          URI uri = URI.create(href);
          if (!uri.isAbsolute() && st.context.baseUri != null) {
            uri = st.context.baseUri.resolve(uri);
          }
          return new AnyURIValue(pc.mapUri(uri).toString());
        }));
  }

  private static EvalState state() throws XPathException {
    EvalState st = EvalState.current();
    if (st == null) {
      throw new XPathException("XProc extension functions are not available in this context",
          "XPST0017");
    }
    return st;
  }

  private static String str(Sequence s) throws XPathException {
    Item i = s.head();
    return i == null ? "" : i.getStringValue();
  }

  static String systemProperty(ProcessorContext pc, String name) throws XPathException {
    QName qn;
    try {
      qn = QNames.parseEQName(name, state().context.namespaces, "XD", 15, 15);
    } catch (XProcException e) {
      throw new XPathException(e.getMessage(), e);
    }
    if (!Ns.XPROC.equals(qn.getNamespace())) {
      return "";
    }
    return switch (qn.getLocalName()) {
      case "episode" -> pc.episode;
      case "locale" -> pc.config.getLocale();
      case "product-name" -> ProcessorContext.PRODUCT_NAME;
      case "product-version" -> ProcessorContext.PRODUCT_VERSION;
      case "vendor" -> ProcessorContext.VENDOR;
      case "vendor-uri" -> ProcessorContext.VENDOR_URI;
      case "version" -> "3.1";
      case "xpath-version" -> "3.1";
      case "psvi-supported" -> "false";
      default -> "";
    };
  }

  static boolean stepAvailable(String name) throws XPathException {
    EvalState st = state();
    QName qn;
    try {
      qn = QNames.parseEQName(name, st.context.namespaces, "XD", 15, 15);
    } catch (XProcException e) {
      throw new XPathException(e.getMessage(), e);
    }
    return st.context.stepAvailable.test(qn);
  }

  static XdmMap documentProperties(ProcessorContext pc, Item item) {
    if (item == null) {
      return new XdmMap();
    }
    XProcDocument doc = pc.registry.lookup(item);
    if (doc != null) {
      return doc.getPropertiesMap();
    }
    Map<XdmAtomicValue, XdmValue> m = new LinkedHashMap<>();
    if (item instanceof NodeInfo node) {
      String base = node.getBaseURI();
      if (base != null && !base.isEmpty()) {
        m.put(new XdmAtomicValue(Ns.BASE_URI), new XdmAtomicValue(URI.create(base)));
      }
      String ct = "application/xml";
      if (node.getNodeKind() == Type.DOCUMENT) {
        XdmNode xn = new XdmNode(node);
        int count = 0;
        boolean text = true;
        for (XdmNode c : xn.children()) {
          count++;
          if (c.getNodeKind() != net.sf.saxon.s9api.XdmNodeKind.TEXT) {
            text = false;
          }
        }
        if (text && count == 1) {
          ct = "text/plain";
        }
      } else if (node.getNodeKind() == Type.TEXT) {
        ct = "text/plain";
      }
      m.put(new XdmAtomicValue(Ns.CONTENT_TYPE), new XdmAtomicValue(ct));
    }
    return new XdmMap(m);
  }

  @FunctionalInterface
  interface Body {
    Sequence call(Sequence[] args) throws XPathException;
  }

  /** A simple extension function definition in the XProc namespace. */
  static final class Fn extends ExtensionFunctionDefinition {
    private final StructuredQName name;
    private final int min;
    private final int max;
    private final SequenceType result;
    private final Body body;

    Fn(String local, int min, int max, SequenceType result, Body body) {
      this.name = new StructuredQName("p", Ns.XPROC, local);
      this.min = min;
      this.max = max;
      this.result = result;
      this.body = body;
    }

    @Override
    public StructuredQName getFunctionQName() {
      return name;
    }

    @Override
    public int getMinimumNumberOfArguments() {
      return min;
    }

    @Override
    public int getMaximumNumberOfArguments() {
      return max;
    }

    @Override
    public SequenceType[] getArgumentTypes() {
      SequenceType[] types = new SequenceType[max];
      for (int i = 0; i < max; i++) {
        types[i] = SequenceType.ANY_SEQUENCE;
      }
      if (name.getLocalPart().equals("urify") && max == 2) {
        types[0] = SequenceType.SINGLE_STRING;
        types[1] = SequenceType.OPTIONAL_STRING;
      }
      return types;
    }

    @Override
    public SequenceType getResultType(SequenceType[] suppliedArgumentTypes) {
      return result;
    }

    @Override
    public boolean hasSideEffects() {
      // prevents Saxon from pre-evaluating calls at compile time
      return true;
    }

    @Override
    public ExtensionFunctionCall makeCallExpression() {
      return new ExtensionFunctionCall() {
        @Override
        public Sequence call(XPathContext context, Sequence[] arguments) throws XPathException {
          try {
            return body.call(arguments);
          } catch (XProcException e) {
            XPathException xe = new XPathException(e.getMessage(), e);
            xe.setErrorCodeQName(new StructuredQName("err", e.getCode().getNamespace(),
                e.getCode().getLocalName()));
            throw xe;
          }
        }
      };
    }
  }
}
