package nl.armatiek.xproc.internal.model;

import java.util.Set;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XPathExecutable;

/**
 * An XPath expression appearing in a pipeline, together with its static context. The compiled
 * form is attached by the parser.
 */
public final class XPathExpr {

  public final String text;
  public final ExprContext context;
  /** True if the expression is a selection pattern rather than an expression. */
  public final boolean pattern;

  public XPathExecutable executable;
  /** A type error detected at compile time; raised when the expression is evaluated. */
  public net.sf.saxon.s9api.SaxonApiException deferredError;
  /** True if the expression depends on the context item, position or size. */
  public boolean usesContext;
  /** True if the expression uses the default collection. */
  public boolean usesCollection;
  /** The variables referenced by the expression. */
  public Set<QName> variableRefs = Set.of();
  /** The external variables of the compiled expression (those that must be bound). */
  public Set<QName> declaredVariables = Set.of();
  /** If the expression consists of a single variable reference: its name. */
  public QName singleVariable;

  public XPathExpr(String text, ExprContext context) {
    this(text, context, false);
  }

  public XPathExpr(String text, ExprContext context, boolean pattern) {
    this.text = text;
    this.context = context;
    this.pattern = pattern;
  }

  @Override
  public String toString() {
    return text;
  }
}
