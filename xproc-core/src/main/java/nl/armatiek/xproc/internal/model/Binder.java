package nl.armatiek.xproc.internal.model;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmValue;

/**
 * Something that binds a value to a variable name: a p:variable, a p:option of a pipeline or a
 * static option.
 */
public interface Binder {

  QName getName();

  /** True if the value is known statically (static options). */
  default boolean isStatic() {
    return false;
  }

  /** The static value (only for static binders). */
  default XdmValue getStaticValue() {
    return null;
  }

  /** The namespace bindings associated with a static value (only for static binders). */
  default java.util.Map<String, String> getStaticNamespaces() {
    return null;
  }
}
