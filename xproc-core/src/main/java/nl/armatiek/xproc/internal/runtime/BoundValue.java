package nl.armatiek.xproc.internal.runtime;

import java.util.Map;

import net.sf.saxon.s9api.XdmValue;

/** The value of a variable or option, together with the namespace bindings that travel with it. */
public record BoundValue(XdmValue value, Map<String, String> namespaces) {
}
