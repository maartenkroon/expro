package nl.armatiek.xproc.internal.xpath;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.sf.saxon.expr.Component;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.StaticContext;
import net.sf.saxon.functions.FunctionLibrary;
import net.sf.saxon.om.FunctionItem;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.query.XQueryFunctionLibrary;
import net.sf.saxon.s9api.XsltPackage;
import net.sf.saxon.trans.SymbolicName;
import net.sf.saxon.trans.Visibility;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.Schema;

/**
 * The functions that one p:import-functions element makes available in the processor XPath
 * context: the public functions of an XSLT package or an XQuery module, restricted to the
 * namespaces of its namespace attribute.
 */
public final class ImportedFunctions implements FunctionLibrary {

  private final FunctionLibrary library;
  private final Set<SymbolicName.F> names;

  private ImportedFunctions(FunctionLibrary library, Set<SymbolicName.F> names) {
    this.library = library;
    this.names = Collections.unmodifiableSet(names);
  }

  /** The public functions of an XSLT package in the given namespaces. */
  public static ImportedFunctions of(XsltPackage pkg, Predicate<String> namespaceFilter) {
    Set<SymbolicName.F> names = new LinkedHashSet<>();
    for (Map.Entry<SymbolicName, Component> e : pkg.getUnderlyingPreparedPackage()
        .getComponentIndex().entrySet()) {
      Visibility v = e.getValue().getVisibility();
      if (e.getKey() instanceof SymbolicName.F f && (v == Visibility.PUBLIC
          || v == Visibility.FINAL) && namespaceFilter.test(namespaceOf(f))) {
        names.add(f);
      }
    }
    return new ImportedFunctions(pkg.getUnderlyingPreparedPackage().getPublicFunctions(), names);
  }

  /** The non-private functions declared in a compiled XQuery in the given namespaces. */
  public static ImportedFunctions of(XQueryFunctionLibrary library,
      Predicate<String> namespaceFilter) throws XPathException {
    Set<SymbolicName.F> names = new LinkedHashSet<>();
    library.processAllFunctions(f -> {
      SymbolicName.F name = new SymbolicName.F(f.getFunctionName(), f.getNumberOfParameters());
      if (!f.isPrivate() && namespaceFilter.test(namespaceOf(name))) {
        names.add(name);
      }
    });
    return new ImportedFunctions(library, names);
  }

  /** The names (with arity) of the imported functions. */
  public Set<SymbolicName.F> names() {
    return names;
  }

  /** Returns the first function of this library that is also in one of the others, or null. */
  public SymbolicName.F firstDuplicate(List<ImportedFunctions> others) {
    for (ImportedFunctions other : others) {
      for (SymbolicName.F name : names) {
        if (other.names.contains(name)) {
          return name;
        }
      }
    }
    return null;
  }

  private static String namespaceOf(SymbolicName.F name) {
    StructuredQName qn = name.getComponentName();
    return qn.getNamespaceUri().toString();
  }

  @Override
  public boolean isAvailable(SymbolicName.F name, Schema schema, int languageLevel) {
    return names.contains(name) && library.isAvailable(name, schema, languageLevel);
  }

  @Override
  public Expression bind(SymbolicName.F name, Expression[] args,
      Map<StructuredQName, Integer> keywords, StaticContext env, List<String> reasons)
      throws XPathException {
    return names.contains(name) ? library.bind(name, args, keywords, env, reasons) : null;
  }

  @Override
  public FunctionItem getFunctionItem(SymbolicName.F name, StaticContext env)
      throws XPathException {
    return names.contains(name) ? library.getFunctionItem(name, env) : null;
  }

  @Override
  public FunctionLibrary copy() {
    return this;
  }
}
