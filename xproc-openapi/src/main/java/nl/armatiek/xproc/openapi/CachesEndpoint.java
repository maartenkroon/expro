package nl.armatiek.xproc.openapi;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import nl.armatiek.xproc.XProcProcessor;

/**
 * Actuator endpoint for the caches of the service: {@code GET /actuator/xproccaches} reports
 * them, {@code DELETE /actuator/xproccaches} clears them: the OpenAPI description is loaded
 * again, and pipelines and XSLT stylesheets are compiled again on their next use.
 */
@Component
@Endpoint(id = "xproccaches")
public class CachesEndpoint {

  private static final Logger LOG = LoggerFactory.getLogger(CachesEndpoint.class);

  private final ApiRegistry registry;
  private final PipelineCache pipelines;
  private final XProcProcessor processor;

  public CachesEndpoint(ApiRegistry registry, PipelineCache pipelines,
      XProcProcessor processor) {
    this.registry = registry;
    this.pipelines = pipelines;
    this.processor = processor;
  }

  @ReadOperation
  public Map<String, Object> caches() {
    var api = registry.get();
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("description", api.file().toString());
    info.put("descriptionLastModified", api.lastModified().toString());
    info.put("basePath", api.basePath());
    info.put("operations", api.operations().size());
    info.put("compiledPipelines", pipelines.size());
    info.put("compiledStylesheets", processor.getCachedStylesheetCount());
    return info;
  }

  @DeleteOperation
  public Map<String, Object> clear() {
    pipelines.clear();
    processor.clearCaches();
    registry.reload();
    LOG.info("Caches cleared");
    return caches();
  }
}
