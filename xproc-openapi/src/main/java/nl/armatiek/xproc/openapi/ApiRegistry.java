package nl.armatiek.xproc.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import nl.armatiek.xproc.openapi.api.ApiDescription;
import nl.armatiek.xproc.openapi.api.ApiDescriptionLoader;

/**
 * Holds the OpenAPI description of the configuration directory. The description is loaded at
 * startup and loaded again when its file changes (if enabled) or when the caches are cleared. A
 * description that fails to load at startup stops the service; one that fails to load later is
 * logged and the previous description stays in use. Thread-safe.
 */
@Component
public class ApiRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ApiRegistry.class);

  private static final List<String> DEFAULT_NAMES = List.of("openapi.yaml", "openapi.yml",
      "openapi.json");

  private final OpenApiProperties props;
  private volatile ApiDescription current;

  public ApiRegistry(OpenApiProperties props) {
    this.props = props;
    this.current = ApiDescriptionLoader.load(descriptionFile(), props.configDir(),
        props.basePath());
  }

  /** The current description, loaded again first if its file has changed. */
  public ApiDescription get() {
    ApiDescription d = current;
    if (props.checkModified() && changed(d)) {
      synchronized (this) {
        if (current == d) {
          reload();
        }
      }
      d = current;
    }
    return d;
  }

  /** Loads the description again. */
  public synchronized void reload() {
    try {
      current = ApiDescriptionLoader.load(descriptionFile(), props.configDir(),
          props.basePath());
    } catch (RuntimeException e) {
      LOG.error("Cannot load the OpenAPI description, keeping the previous one: {}",
          e.getMessage());
    }
  }

  /** The description file in the configuration directory. */
  public Path descriptionFile() {
    if (props.description() != null) {
      return props.configDir().resolve(props.description());
    }
    for (String name : DEFAULT_NAMES) {
      Path p = props.configDir().resolve(name);
      if (Files.isRegularFile(p)) {
        return p;
      }
    }
    return props.configDir().resolve(DEFAULT_NAMES.get(0));
  }

  private boolean changed(ApiDescription d) {
    Path file = descriptionFile();
    if (!file.equals(d.file())) {
      return true;
    }
    try {
      FileTime t = Files.getLastModifiedTime(file);
      return !t.equals(d.lastModified());
    } catch (IOException e) {
      return false;
    }
  }
}
