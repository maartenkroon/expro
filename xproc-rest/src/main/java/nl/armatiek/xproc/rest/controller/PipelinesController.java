package nl.armatiek.xproc.rest.controller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import nl.armatiek.xproc.rest.api.PipelinesApi;
import nl.armatiek.xproc.rest.model.PipelineDescription;
import nl.armatiek.xproc.rest.model.RunRequest;
import nl.armatiek.xproc.rest.model.RunResult;
import nl.armatiek.xproc.rest.service.BadRequestException;
import nl.armatiek.xproc.rest.service.DocumentConverter;
import nl.armatiek.xproc.rest.service.Names;
import nl.armatiek.xproc.rest.service.PipelineRegistry;
import nl.armatiek.xproc.rest.service.PipelineRegistry.StoredPipeline;
import nl.armatiek.xproc.rest.service.XProcService;

@RestController
public class PipelinesController implements PipelinesApi {

  private final PipelineRegistry registry;
  private final XProcService service;
  private final HttpServletRequest request;

  public PipelinesController(PipelineRegistry registry, XProcService service,
      HttpServletRequest request) {
    this.registry = registry;
    this.service = service;
    this.request = request;
  }

  @Override
  public ResponseEntity<List<PipelineDescription>> listPipelines() {
    return ResponseEntity.ok(registry.list().stream().map(service::describe).toList());
  }

  @Override
  public ResponseEntity<PipelineDescription> putPipeline(String pipelineId, Resource body,
      URI baseUri, List<String> staticOption, List<String> namespace) {
    byte[] source = bytes(body);
    if (source == null || source.length == 0) {
      throw new BadRequestException("The request body must contain a pipeline document");
    }
    StoredPipeline sp = service.compile(pipelineId, source, baseUri,
        Names.pairs(staticOption, "staticOption"), Names.pairs(namespace, "namespace"));
    boolean replaced = registry.put(sp);
    return ResponseEntity.status(replaced ? HttpStatus.OK : HttpStatus.CREATED)
        .body(service.describe(sp));
  }

  @Override
  public ResponseEntity<PipelineDescription> getPipeline(String pipelineId) {
    return ResponseEntity.ok(service.describe(registry.require(pipelineId)));
  }

  @Override
  public ResponseEntity<Void> deletePipeline(String pipelineId) {
    if (!registry.remove(pipelineId)) {
      registry.require(pipelineId);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Resource> getPipelineSource(String pipelineId) {
    StoredPipeline sp = registry.require(pipelineId);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
        .body(new ByteArrayResource(sp.source()));
  }

  @Override
  public ResponseEntity<RunResult> runPipeline(String pipelineId, RunRequest runRequest) {
    StoredPipeline sp = registry.require(pipelineId);
    return ResponseEntity.ok(service.run(sp.pipeline(), new XProcService.RunSpec(
        runRequest.getInputs(), runRequest.getOptions(), runRequest.getXpathOptions(),
        runRequest.getNamespaces(), runRequest.getOutputs())));
  }

  @Override
  public ResponseEntity<Resource> transform(String pipelineId, List<String> option,
      List<String> namespace, URI baseUri, Resource body) {
    StoredPipeline sp = registry.require(pipelineId);
    byte[] input = bytes(body);
    if (input != null && input.length == 0) {
      input = null;
    }
    DocumentConverter.Serialized out = service.transform(sp.pipeline(), input,
        request.getContentType(), baseUri, Names.pairs(option, "option"),
        Names.pairs(namespace, "namespace"));
    if (out == null) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, out.contentType())
        .body(new ByteArrayResource(out.bytes()));
  }

  private static byte[] bytes(Resource body) {
    if (body == null) {
      return null;
    }
    try (InputStream in = body.getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new BadRequestException("Cannot read the request body: " + e.getMessage(), e);
    }
  }
}
