package nl.armatiek.xproc.internal.steps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.sf.saxon.s9api.QName;
import nl.armatiek.xproc.Ns;

/** Maps step types to their implementations. */
public final class StepRegistry {

  private final Map<QName, Supplier<StepImplementation>> map = new ConcurrentHashMap<>();

  public StepRegistry() {
    StandardSteps.registerAll(this);
  }

  public void register(QName type, Supplier<StepImplementation> factory) {
    map.put(type, factory);
  }

  public void register(String xprocLocalName, Supplier<StepImplementation> factory) {
    register(new QName(Ns.XPROC, xprocLocalName), factory);
  }

  public boolean isAvailable(QName type) {
    return map.containsKey(type);
  }

  public StepImplementation create(QName type) {
    Supplier<StepImplementation> f = map.get(type);
    return f == null ? null : f.get();
  }
}
