package nl.armatiek.xproc.internal.model;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.internal.xpath.ImportedFunctions;

/**
 * The static context of an expression: in-scope namespaces, base URI, location, the in-scope
 * variable bindings and imported function libraries.
 */
public final class ExprContext {

  /** In-scope namespaces; the default namespace (if any) is stored under the key "". */
  public final Map<String, String> namespaces;
  public final URI baseUri;
  public final Location location;
  /** In-scope variable bindings (in lexical order; later entries shadow earlier ones). */
  public final Map<net.sf.saxon.s9api.QName, Binder> bindings;
  public final List<ImportedFunctions> functionLibraries;
  /** Answers p:step-available() for expressions in this context. */
  public java.util.function.Predicate<net.sf.saxon.s9api.QName> stepAvailable = q -> false;

  public ExprContext(Map<String, String> namespaces, URI baseUri, Location location,
      Map<net.sf.saxon.s9api.QName, Binder> bindings, List<ImportedFunctions> functionLibraries) {
    this.namespaces = Collections.unmodifiableMap(new LinkedHashMap<>(namespaces));
    this.baseUri = baseUri;
    this.location = location;
    this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    this.functionLibraries = functionLibraries == null ? List.of() : functionLibraries;
  }

  public static Map<String, String> namespacesOf(XdmNode element) {
    Map<String, String> ns = new LinkedHashMap<>();
    for (XdmNode n : element.axisIterator(net.sf.saxon.s9api.Axis.NAMESPACE).stream().toList()) {
      String prefix = n.getNodeName() == null ? "" : n.getNodeName().getLocalName();
      ns.put(prefix, n.getStringValue());
    }
    return ns;
  }

  public ExprContext withBindings(Map<net.sf.saxon.s9api.QName, Binder> b) {
    ExprContext c = new ExprContext(namespaces, baseUri, location, b, functionLibraries);
    c.stepAvailable = stepAvailable;
    return c;
  }

  public ExprContext withNamespaces(Map<String, String> ns) {
    ExprContext c = new ExprContext(ns, baseUri, location, bindings, functionLibraries);
    c.stepAvailable = stepAvailable;
    return c;
  }

  /** Namespaces without the default namespace binding. */
  public Map<String, String> prefixedNamespaces() {
    Map<String, String> m = new LinkedHashMap<>(namespaces);
    m.remove("");
    return m;
  }
}
