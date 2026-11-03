package nl.armatiek.xproc.internal.xpath;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.Operand;
import net.sf.saxon.expr.StaticProperty;
import net.sf.saxon.expr.VariableReference;
import net.sf.saxon.functions.CollectionFn;
import net.sf.saxon.expr.SystemFunctionCall;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.trans.XPathException;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.model.ExprContext;
import nl.armatiek.xproc.internal.model.XPathExpr;

/**
 * Compiles and evaluates XPath expressions in the processor XPath context.
 */
public final class XPathEngine {

  /** The state of the context item for an evaluation. */
  public enum ContextState {
    /** A context item is available. */
    DEFINED,
    /** No document available (or no connection at all). */
    UNDEFINED,
    /** A sequence of documents was provided. */
    SEQUENCE
  }

  public static final String XQT_ERRORS = "http://www.w3.org/2005/xqt-errors";

  private final Processor processor;
  private final Map<String, XPathExecutable> stepCache = new ConcurrentHashMap<>();

  public XPathEngine(Processor processor) {
    this.processor = processor;
  }

  public Processor getProcessor() {
    return processor;
  }

  /**
   * Compiles an expression in the processor XPath context, recording whether it uses the
   * context item and which variables it references.
   *
   * @throws XProcException err:XS0107 for static errors in the expression
   */
  public void compile(XPathExpr expr) {
    if (expr.executable != null || expr.deferredError != null) {
      return;
    }
    XPathCompiler xc = newCompiler(expr.context, true);
    try {
      XPathExecutable exec = expr.pattern ? xc.compilePattern(expr.text) : xc.compile(expr.text);
      expr.executable = exec;
      Set<QName> declared = new HashSet<>();
      exec.iterateExternalVariables().forEachRemaining(declared::add);
      expr.declaredVariables = declared;
      Expression internal = exec.getUnderlyingExpression().getInternalExpression();
      expr.usesContext = (internal.getDependencies() & StaticProperty.DEPENDS_ON_FOCUS) != 0;
      Set<QName> vars = new HashSet<>();
      boolean[] usesCollection = new boolean[1];
      collect(internal, vars, usesCollection);
      expr.variableRefs = vars;
      expr.usesCollection = usesCollection[0];
      if (internal instanceof VariableReference vr) {
        expr.singleVariable = new QName(vr.getVariableName());
      }
    } catch (SaxonApiException e) {
      QName code = e.getErrorCode();
      if (code != null && code.getLocalName().startsWith("XPTY") && !expr.pattern) {
        // type errors must not be raised statically
        expr.deferredError = e;
        expr.declaredVariables = Set.of();
        return;
      }
      throw staticError(expr, e);
    }
  }

  private static void collect(Expression e, Set<QName> vars, boolean[] usesCollection) {
    if (e instanceof VariableReference vr) {
      vars.add(new QName(vr.getVariableName()));
    }
    if (e instanceof SystemFunctionCall sfc && sfc.getTargetFunction() instanceof CollectionFn) {
      usesCollection[0] = true;
    }
    for (Operand o : e.operands()) {
      collect(o.getChildExpression(), vars, usesCollection);
    }
  }

  private XProcException staticError(XPathExpr expr, SaxonApiException e) {
    XProcException xe = XProcException.xs(107, "Static error in XPath expression '"
        + expr.text + "': " + e.getMessage());
    if (e.getErrorCode() != null) {
      xe.setCauseCode(e.getErrorCode());
    }
    return expr.context.location.apply(xe);
  }

  /** Creates a compiler for the processor XPath context. */
  public XPathCompiler newCompiler(ExprContext ctx, boolean declareVariables) {
    XPathCompiler xc = processor.newXPathCompiler();
    xc.setLanguageVersion("3.1");
    undeclareDefaults(xc, ctx.namespaces);
    for (Map.Entry<String, String> e : ctx.namespaces.entrySet()) {
      if (!e.getKey().isEmpty()) {
        xc.declareNamespace(e.getKey(), e.getValue());
      }
    }
    if (ctx.baseUri != null) {
      xc.setBaseURI(ctx.baseUri);
    }
    if (declareVariables) {
      for (QName name : ctx.bindings.keySet()) {
        xc.declareVariable(name);
      }
    }
    if (!ctx.functionLibraries.isEmpty()) {
      var functions = (net.sf.saxon.functions.FunctionLibraryList)
          ((net.sf.saxon.sxpath.AbstractStaticContext) xc.getUnderlyingStaticContext())
          .getFunctionLibrary();
      for (ImportedFunctions f : ctx.functionLibraries) {
        functions.addFunctionLibrary(f);
      }
    }
    return xc;
  }

  /**
   * Evaluates a (compiled) expression in the processor XPath context.
   *
   * @param expr the expression
   * @param contextItem the context item (may be null)
   * @param state the context state
   * @param variables variable values
   * @param iteration iteration position and size (may be null)
   * @param collection the default collection (may be null)
   */
  public XdmValue evaluate(XPathExpr expr, XdmItem contextItem, ContextState state,
      Map<QName, XdmValue> variables, int[] iteration, List<XdmItem> collection,
      boolean staticAnalysis) {
    compile(expr);
    if (expr.deferredError != null) {
      throw dynamicError(expr, expr.deferredError, state);
    }
    EvalState prev = EvalState.push(new EvalState(expr.context,
        iteration == null ? 1 : iteration[0], iteration == null ? 1 : iteration[1],
        collection, staticAnalysis));
    try {
      XPathSelector sel = expr.executable.load();
      if (contextItem != null && state == ContextState.DEFINED) {
        sel.setContextItem(contextItem);
      }
      for (QName name : expr.declaredVariables) {
        XdmValue v = variables.get(name);
        sel.setVariable(name, v == null ? XdmEmptySequence.getInstance() : v);
      }
      return sel.evaluate();
    } catch (SaxonApiException e) {
      throw dynamicError(expr, e, state);
    } catch (XProcException e) {
      throw expr.context.location.apply(e);
    } finally {
      EvalState.restore(prev);
    }
  }

  private static final String[] SAXON_DEFAULT_PREFIXES = {"xs", "xsi", "saxon", "fn", "math",
      "map", "array", "err", "local"};

  /**
   * Removes Saxon's predeclared namespace prefixes that are not in scope: only the in-scope
   * namespaces of the element are statically known (XProc 3.1, appendix B).
   */
  private static void undeclareDefaults(XPathCompiler xc, Map<String, String> inScope) {
    for (String prefix : SAXON_DEFAULT_PREFIXES) {
      if (!inScope.containsKey(prefix)) {
        try {
          xc.declareNamespace(prefix, "");
        } catch (RuntimeException e) {
          // ignore
        }
      }
    }
  }

  /** Maps a Saxon error to an XProc error. */
  public XProcException dynamicError(XPathExpr expr, SaxonApiException e, ContextState state) {
    Throwable cause = e.getCause();
    while (cause != null) {
      if (cause instanceof XProcException xe) {
        return expr.context.location.apply(xe);
      }
      cause = cause.getCause();
    }
    QName code = e.getErrorCode();
    XProcException xe;
    if (code != null && XQT_ERRORS.equals(code.getNamespace())
        && ("XPDY0002".equals(code.getLocalName()))) {
      if (state == ContextState.SEQUENCE) {
        xe = XProcException.xd(65, "The context item is a sequence of documents in '"
            + expr.text + "'");
      } else {
        xe = XProcException.xd(1, "The context item is undefined in '" + expr.text + "'");
      }
    } else if (code != null) {
      xe = new XProcException(code, e.getMessage(), e);
    } else {
      xe = XProcException.xd(83, "Failed to evaluate '" + expr.text + "': " + e.getMessage(), e);
    }
    xe.setCauseCode(code);
    if (e.getCause() instanceof XPathException xpe && xpe.getErrorObject() != null) {
      // fn:error() with an error object: keep it for the error document
    }
    return expr.context.location.apply(xe);
  }

  /**
   * Compiles an expression for evaluation by a step (step XPath context: no XProc functions,
   * no variables unless given).
   */
  public XPathExecutable compileForStep(String text, Map<String, String> namespaces, URI baseUri,
      List<QName> variables, boolean pattern) {
    StringBuilder key = new StringBuilder(pattern ? "P" : "E").append(text).append('\u0000');
    for (Map.Entry<String, String> e : namespaces.entrySet()) {
      key.append(e.getKey()).append('=').append(e.getValue()).append(' ');
    }
    key.append('\u0000').append(baseUri).append('\u0000').append(variables);
    return stepCache.computeIfAbsent(key.toString(), k -> {
      XPathCompiler xc = processor.newXPathCompiler();
      xc.setLanguageVersion("3.1");
      undeclareDefaults(xc, namespaces);
      for (Map.Entry<String, String> e : namespaces.entrySet()) {
        if (!e.getKey().isEmpty()) {
          xc.declareNamespace(e.getKey(), e.getValue());
        }
      }
      if (baseUri != null) {
        xc.setBaseURI(baseUri);
      }
      if (variables != null) {
        for (QName v : variables) {
          xc.declareVariable(v);
        }
      }
      try {
        return pattern ? xc.compilePattern(text) : xc.compile(text);
      } catch (SaxonApiException e) {
        throw new XProcException(e.getErrorCode() == null ? XProcException.errCode("XD", 83)
            : e.getErrorCode(), "Invalid XPath " + (pattern ? "pattern" : "expression") + " '"
                + text + "': " + e.getMessage(), e);
      }
    });
  }

  /** Convenience: evaluates an expression string in a step context. */
  public XdmValue evaluateForStep(String text, Map<String, String> namespaces, URI baseUri,
      XdmItem context, Map<QName, XdmValue> vars) {
    XPathExecutable exec = compileForStep(text, namespaces, baseUri,
        vars == null ? List.of() : new ArrayList<>(vars.keySet()), false);
    try {
      XPathSelector sel = exec.load();
      if (context != null) {
        sel.setContextItem(context);
      }
      if (vars != null) {
        Set<QName> declared = new HashSet<>();
        exec.iterateExternalVariables().forEachRemaining(declared::add);
        for (Map.Entry<QName, XdmValue> e : vars.entrySet()) {
          if (declared.contains(e.getKey())) {
            sel.setVariable(e.getKey(), e.getValue());
          }
        }
      }
      return sel.evaluate();
    } catch (SaxonApiException e) {
      throw new XProcException(e.getErrorCode() == null ? XProcException.errCode("XD", 83)
          : e.getErrorCode(), e.getMessage(), e);
    }
  }

  /** Default namespace bindings used by the step library declarations. */
  public static Map<String, String> standardNamespaces() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("xs", Ns.XS);
    m.put("p", Ns.XPROC);
    m.put("c", Ns.XPROC_STEP);
    m.put("err", Ns.XPROC_ERROR);
    m.put("map", Ns.MAP);
    m.put("array", Ns.ARRAY);
    m.put("math", Ns.MATH);
    m.put("fn", Ns.FN);
    return m;
  }
}
