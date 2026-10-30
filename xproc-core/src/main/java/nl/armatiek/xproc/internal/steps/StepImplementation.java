package nl.armatiek.xproc.internal.steps;

/** The implementation of an external (atomic) step. */
@FunctionalInterface
public interface StepImplementation {

  /**
   * Runs the step. Inputs and options are read from the context; results are written to it.
   */
  void run(StepContext ctx);
}
