package nl.armatiek.xproc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The documents produced on the output ports of a pipeline run. */
public final class XProcResult {

  private final Map<String, List<XProcDocument>> outputs;
  private final String primaryPort;

  XProcResult(Map<String, List<XProcDocument>> outputs, String primaryPort) {
    Map<String, List<XProcDocument>> m = new LinkedHashMap<>();
    for (Map.Entry<String, List<XProcDocument>> e : outputs.entrySet()) {
      m.put(e.getKey(), List.copyOf(e.getValue()));
    }
    this.outputs = Collections.unmodifiableMap(m);
    this.primaryPort = primaryPort;
  }

  /** All outputs, by port name. */
  public Map<String, List<XProcDocument>> getOutputs() {
    return outputs;
  }

  /** The documents on an output port (empty if the port does not exist). */
  public List<XProcDocument> getDocuments(String port) {
    return outputs.getOrDefault(port, List.of());
  }

  /** The name of the primary output port, or null. */
  public String getPrimaryPort() {
    return primaryPort;
  }

  /** The documents on the primary output port. */
  public List<XProcDocument> getPrimaryDocuments() {
    return primaryPort == null ? List.of() : getDocuments(primaryPort);
  }
}
