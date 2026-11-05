package nl.armatiek.xproc.openapi.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import net.sf.saxon.om.NameChecker;
import net.sf.saxon.s9api.QName;
import nl.armatiek.xproc.openapi.OpenApiNs;

/**
 * Loads an OpenAPI 3.0 or 3.1 description (YAML or JSON) with the Swagger parser, which also
 * resolves references, and derives the operations and their pipelines from it.
 */
public final class ApiDescriptionLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ApiDescriptionLoader.class);

  /** operationIds that can be used as a file name in the configuration directory. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

  private static final PathPatternParser PATTERNS = new PathPatternParser();

  private ApiDescriptionLoader() {
  }

  /** An OpenAPI description that cannot be used. */
  public static final class InvalidDescriptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidDescriptionException(String message) {
      super(message);
    }
  }

  /**
   * Loads a description.
   *
   * @param file the description file
   * @param configDir the directory with the pipelines
   * @param basePathOverride the base path to use instead of the one of the first server, or null
   */
  public static ApiDescription load(Path file, Path configDir, String basePathOverride) {
    FileTime modified;
    try {
      modified = Files.getLastModifiedTime(file);
    } catch (IOException e) {
      throw new InvalidDescriptionException("Cannot read the OpenAPI description " + file
          + ": " + e.getMessage());
    }
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    SwaggerParseResult result = new OpenAPIV3Parser().readLocation(file.toUri().toString(),
        null, options);
    OpenAPI api = result.getOpenAPI();
    if (api == null) {
      throw new InvalidDescriptionException("Cannot parse the OpenAPI description " + file
          + messages(result.getMessages()));
    }
    if (result.getMessages() != null) {
      result.getMessages().forEach(m -> LOG.warn("{}: {}", file.getFileName(), m));
    }
    String basePath = normalizeBasePath(basePathOverride != null ? basePathOverride
        : serverPath(api.getServers()));
    List<OperationSpec> operations = new ArrayList<>();
    if (api.getPaths() != null) {
      for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
        PathPattern pattern;
        try {
          pattern = PATTERNS.parse(e.getKey());
        } catch (RuntimeException ex) {
          throw new InvalidDescriptionException("Invalid path template " + e.getKey() + ": "
              + ex.getMessage());
        }
        PathItem item = e.getValue();
        for (Map.Entry<PathItem.HttpMethod, Operation> o : item.readOperationsMap().entrySet()) {
          operations.add(operation(api, configDir, o.getKey().name(), e.getKey(), pattern, item,
              o.getValue()));
        }
      }
    }
    // Most specific path template first (a concrete path before a matching template); the sort
    // is stable, so the operations of one path stay in the order of the description
    operations.sort(Comparator.comparing(OperationSpec::pattern,
        PathPattern.SPECIFICITY_COMPARATOR));
    LOG.info("Loaded OpenAPI description {} with {} operations under base path '{}'", file,
        operations.size(), basePath);
    return new ApiDescription(file, modified, basePath, List.copyOf(operations));
  }

  private static OperationSpec operation(OpenAPI api, Path configDir, String method,
      String template, PathPattern pattern, PathItem item, Operation op) {
    // Operation parameters override the path item parameters with the same name and location
    Map<String, Parameter> params = new LinkedHashMap<>();
    for (List<Parameter> list : java.util.Arrays.asList(item.getParameters(),
        op.getParameters())) {
      if (list != null) {
        for (Parameter p : list) {
          p = resolve(api, p);
          if (p != null && p.getName() != null && p.getIn() != null) {
            params.put(p.getIn() + "\u0000" + p.getName(), p);
          }
        }
      }
    }
    List<ParameterSpec> specs = new ArrayList<>();
    for (Parameter p : params.values()) {
      ParameterSpec spec = parameter(p, template);
      if (spec != null) {
        specs.add(spec);
      }
    }
    String id = op.getOperationId();
    Path pipeline = null;
    if (id == null) {
      LOG.warn("{} {} has no operationId; requests are answered with 501", method, template);
    } else if (!SAFE_ID.matcher(id).matches()) {
      LOG.warn("The operationId '{}' of {} {} cannot be used as a pipeline file name; requests"
          + " are answered with 501", id, method, template);
    } else {
      pipeline = configDir.resolve(id + ".xpl");
      if (!Files.isRegularFile(pipeline)) {
        LOG.warn("The pipeline {} of {} {} does not exist (yet)", pipeline, method, template);
      }
    }
    return new OperationSpec(method, template, pattern, id, pipeline, List.copyOf(specs),
        responseContentTypes(op));
  }

  private static Parameter resolve(OpenAPI api, Parameter p) {
    String ref = p.get$ref();
    if (ref == null) {
      return p;
    }
    String prefix = "#/components/parameters/";
    if (ref.startsWith(prefix) && api.getComponents() != null
        && api.getComponents().getParameters() != null) {
      Parameter target = api.getComponents().getParameters().get(ref.substring(prefix
          .length()));
      if (target != null) {
        return resolve(api, target);
      }
    }
    LOG.warn("Cannot resolve the parameter reference {}", ref);
    return null;
  }

  private static ParameterSpec parameter(Parameter p, String template) {
    String in = p.getIn().toLowerCase(Locale.ROOT);
    if (!List.of("path", "query", "header", "cookie").contains(in)) {
      return null;
    }
    QName option = null;
    if (NameChecker.isValidNCName(p.getName())) {
      option = new QName(OpenApiNs.forLocation(in), p.getName());
    } else {
      LOG.warn("The {} parameter '{}' of {} is not a valid XML name; it is not passed to the "
          + "pipeline", in, p.getName(), template);
    }
    Schema<?> schema = p.getSchema();
    boolean array = schema != null && ("array".equals(schema.getType())
        || schema.getTypes() != null && schema.getTypes().contains("array"));
    String style = p.getStyle() != null ? p.getStyle().toString()
        : in.equals("query") || in.equals("cookie") ? "form" : "simple";
    boolean explode = p.getExplode() != null ? p.getExplode() : style.equals("form");
    List<String> defaults = new ArrayList<>();
    if (schema != null && schema.getDefault() != null) {
      Object d = schema.getDefault();
      if (d instanceof Collection<?> c) {
        c.forEach(v -> defaults.add(String.valueOf(v)));
      } else {
        defaults.add(String.valueOf(d));
      }
    }
    boolean required = in.equals("path") || Boolean.TRUE.equals(p.getRequired());
    return new ParameterSpec(p.getName(), in, required, array, style, explode,
        List.copyOf(defaults), option);
  }

  private static List<String> responseContentTypes(Operation op) {
    List<String> types = new ArrayList<>();
    if (op.getResponses() == null) {
      return types;
    }
    // Success responses (2xx) first, then the others (default, errors)
    List<Map.Entry<String, ApiResponse>> responses = new ArrayList<>(op.getResponses()
        .entrySet());
    responses.sort(Comparator.comparing(e -> e.getKey().startsWith("2") ? 0 : 1));
    for (Map.Entry<String, ApiResponse> r : responses) {
      Content content = r.getValue().getContent();
      if (content != null) {
        content.keySet().stream().filter(t -> !types.contains(t)).forEach(types::add);
      }
    }
    return types;
  }

  private static String serverPath(List<Server> servers) {
    if (servers == null || servers.isEmpty() || servers.get(0).getUrl() == null) {
      return "";
    }
    Server server = servers.get(0);
    String url = server.getUrl();
    if (server.getVariables() != null) {
      for (var v : server.getVariables().entrySet()) {
        if (v.getValue().getDefault() != null) {
          url = url.replace("{" + v.getKey() + "}", v.getValue().getDefault());
        }
      }
    }
    try {
      String path = URI.create(url).getRawPath();
      return path == null ? "" : path;
    } catch (IllegalArgumentException e) {
      LOG.warn("Cannot determine the base path of server URL {}", url);
      return "";
    }
  }

  private static String normalizeBasePath(String path) {
    String p = path == null ? "" : path.trim();
    while (p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    if (!p.isEmpty() && !p.startsWith("/")) {
      p = "/" + p;
    }
    return p;
  }

  private static String messages(List<String> messages) {
    return messages == null || messages.isEmpty() ? "" : ": " + String.join("; ", messages);
  }
}
