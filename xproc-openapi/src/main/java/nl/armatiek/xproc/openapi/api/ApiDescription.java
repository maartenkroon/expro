package nl.armatiek.xproc.openapi.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.pattern.PathPattern;

import nl.armatiek.xproc.openapi.web.ApiException;

/**
 * A loaded OpenAPI description: its operations in matching order (most specific path first).
 * Immutable and thread-safe.
 *
 * @param file the description file
 * @param lastModified the modification time of the file when it was loaded
 * @param basePath the path under which the API paths are served ("" for the root)
 * @param operations the operations, most specific path template first
 */
public record ApiDescription(Path file, FileTime lastModified, String basePath,
    List<OperationSpec> operations) {

  /** An operation that matches a request, with the values of its path parameters. */
  public record Match(OperationSpec operation, Map<String, String> pathVariables) {
  }

  /**
   * Finds the operation for a request.
   *
   * @param method the HTTP method
   * @param path the request path within the application (not decoded)
   * @throws ApiException 404 if no path matches, 405 if no matching path has the method
   */
  public Match find(String method, String path) {
    String apiPath;
    if (basePath.isEmpty()) {
      apiPath = path;
    } else if (path.equals(basePath) || path.startsWith(basePath + "/")) {
      apiPath = path.substring(basePath.length());
    } else {
      throw new ApiException(HttpStatus.NOT_FOUND, "No API path matches " + path);
    }
    if (apiPath.isEmpty()) {
      apiPath = "/";
    }
    PathContainer container = PathContainer.parsePath(apiPath);
    Set<String> allowed = new TreeSet<>();
    for (OperationSpec op : operations) {
      PathPattern.PathMatchInfo info = op.pattern().matchAndExtract(container);
      if (info == null) {
        continue;
      }
      if (op.method().equals(method)) {
        // The captured values are percent-encoded
        Map<String, String> variables = new LinkedHashMap<>();
        info.getUriVariables().forEach((k, v) -> variables.put(k, UriUtils.decode(v,
            StandardCharsets.UTF_8)));
        return new Match(op, variables);
      }
      allowed.add(op.method());
    }
    if (allowed.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "No API path matches " + path);
    }
    throw new ApiException(HttpStatus.METHOD_NOT_ALLOWED, "Method " + method
        + " is not allowed for " + path, Map.of("Allow", String.join(", ", allowed)), null);
  }
}
