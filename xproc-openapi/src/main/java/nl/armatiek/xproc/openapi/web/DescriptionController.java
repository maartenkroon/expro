package nl.armatiek.xproc.openapi.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;
import nl.armatiek.xproc.openapi.ApiRegistry;

/**
 * Serves the OpenAPI description file as it is (YAML or JSON), at
 * {@code xproc.openapi.description-path}. Swagger UI displays it from there. The file that is
 * currently loaded is served, so a reloaded description shows up at once.
 */
@Hidden
@RestController
public class DescriptionController {

  private static final MediaType YAML = MediaType.parseMediaType("application/yaml;charset=utf-8");

  private final ApiRegistry registry;

  public DescriptionController(ApiRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("${xproc.openapi.description-path:/openapi}")
  public ResponseEntity<byte[]> description() throws IOException {
    Path file = registry.get().file();
    boolean json = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    return ResponseEntity.ok()
        .contentType(json ? MediaType.APPLICATION_JSON : YAML)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .body(Files.readAllBytes(file));
  }
}
