package nl.armatiek.xproc.rest.service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.rest.XProcProperties;

/** The compiled pipelines stored by clients, by identifier (in memory). */
@Component
public class PipelineRegistry {

  /** A stored pipeline with its source. */
  public record StoredPipeline(String id, XProcPipeline pipeline, byte[] source, URI baseUri,
      OffsetDateTime compiledAt) {
  }

  private final Map<String, StoredPipeline> pipelines = new ConcurrentHashMap<>();
  private final int maxPipelines;

  public PipelineRegistry(XProcProperties props) {
    this.maxPipelines = props.maxPipelines();
  }

  /** Stores a pipeline. Returns true if it replaced an existing pipeline. */
  public synchronized boolean put(StoredPipeline pipeline) {
    if (!pipelines.containsKey(pipeline.id()) && pipelines.size() >= maxPipelines) {
      throw new BadRequestException("The maximum number of stored pipelines (" + maxPipelines
          + ") has been reached");
    }
    return pipelines.put(pipeline.id(), pipeline) != null;
  }

  public Optional<StoredPipeline> get(String id) {
    return Optional.ofNullable(pipelines.get(id));
  }

  public StoredPipeline require(String id) {
    return get(id).orElseThrow(() -> new PipelineNotFoundException(id));
  }

  public boolean remove(String id) {
    return pipelines.remove(id) != null;
  }

  public List<StoredPipeline> list() {
    return pipelines.values().stream()
        .sorted(Comparator.comparing(StoredPipeline::id))
        .toList();
  }
}
