package nl.armatiek.xproc.rest.controller;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import nl.armatiek.xproc.rest.api.RunsApi;
import nl.armatiek.xproc.rest.model.AdHocRunRequest;
import nl.armatiek.xproc.rest.model.PipelineSource;
import nl.armatiek.xproc.rest.model.RunResult;
import nl.armatiek.xproc.rest.service.XProcService;

@RestController
public class RunsController implements RunsApi {

  private final XProcService service;

  public RunsController(XProcService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<RunResult> runAdHoc(AdHocRunRequest req) {
    PipelineSource src = req.getPipeline();
    var compiled = service.compile("ad-hoc", src.getSource().getBytes(StandardCharsets.UTF_8),
        src.getBaseUri(), src.getStaticOptions() == null ? Map.of() : src.getStaticOptions(),
        req.getNamespaces());
    return ResponseEntity.ok(service.run(compiled.pipeline(), new XProcService.RunSpec(
        req.getInputs(), req.getOptions(), req.getXpathOptions(), req.getNamespaces(),
        req.getOutputs())));
  }
}
