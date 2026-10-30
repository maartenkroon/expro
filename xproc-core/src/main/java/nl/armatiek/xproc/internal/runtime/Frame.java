package nl.armatiek.xproc.internal.runtime;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.internal.model.Binder;
import nl.armatiek.xproc.internal.model.Node;

/**
 * The runtime environment of one evaluation of a subpipeline: the documents on readable ports
 * and the values of in-scope variables and options.
 */
public final class Frame {

  public record PortKey(Node node, String port) {
  }

  public final Frame parent;
  private final Map<PortKey, List<XProcDocument>> ports = new HashMap<>();
  private final Map<Binder, BoundValue> values = new HashMap<>();
  /** Iteration position and size, or null to inherit from the parent. */
  public int[] iteration;

  public Frame(Frame parent) {
    this.parent = parent;
  }

  public void putPort(Node node, String port, List<XProcDocument> docs) {
    ports.put(new PortKey(node, port), docs);
  }

  public List<XProcDocument> lookupPort(Node node, String port) {
    PortKey key = new PortKey(node, port);
    for (Frame f = this; f != null; f = f.parent) {
      List<XProcDocument> docs = f.ports.get(key);
      if (docs != null) {
        return docs;
      }
    }
    return null;
  }

  public void putValue(Binder binder, BoundValue value) {
    values.put(binder, value);
  }

  public BoundValue lookupValue(Binder binder) {
    if (binder.isStatic()) {
      return new BoundValue(binder.getStaticValue(), binder.getStaticNamespaces());
    }
    for (Frame f = this; f != null; f = f.parent) {
      BoundValue v = f.values.get(binder);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /** Variable values for an expression with the given in-scope bindings. */
  public Map<QName, XdmValue> variables(Map<QName, Binder> bindings) {
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    for (Map.Entry<QName, Binder> e : bindings.entrySet()) {
      BoundValue v = lookupValue(e.getValue());
      if (v != null) {
        vars.put(e.getKey(), v.value());
      }
    }
    return vars;
  }

  public int[] iteration() {
    for (Frame f = this; f != null; f = f.parent) {
      if (f.iteration != null) {
        return f.iteration;
      }
    }
    return null;
  }
}
