package nl.armatiek.xproc.internal.steps;

/** Registers the implementations of the standard and additional step libraries. */
final class StandardSteps {

  private StandardSteps() {
  }

  static void registerAll(StepRegistry r) {
    MiscSteps.register(r);
    XmlSteps.register(r);
    TextSteps.register(r);
    TransformSteps.register(r);
    ArchiveSteps.register(r);
    FileSteps.register(r);
    OsSteps.register(r);
    ValidationSteps.register(r);
    r.register("run", RunStep::new);
    r.register("xinclude", XIncludeStep::new);
    r.register("http-request", HttpRequestStep::new);
  }
}
