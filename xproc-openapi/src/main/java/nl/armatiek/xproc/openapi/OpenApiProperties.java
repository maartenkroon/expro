package nl.armatiek.xproc.openapi;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the service ({@code xproc.openapi.*}).
 *
 * @param configDir the directory with the OpenAPI description and the pipelines
 * @param description the file name of the OpenAPI description in the configuration directory;
 *        if not set, the first of openapi.yaml, openapi.yml and openapi.json that exists
 * @param basePath the path under which the API paths are served; if not set, the path of the
 *        first server URL of the description (or the root)
 * @param descriptionPath the path at which the description file is served (for Swagger UI)
 * @param checkModified whether the description and pipeline files are checked for changes on
 *        each request (changed files are loaded again)
 * @param maxRequestSize the maximum size in bytes of a request body that is not multipart
 * @param allowFileAccess whether pipelines may read and write file: URIs
 * @param allowNetworkAccess whether pipelines may access the network
 * @param allowOsAccess whether pipelines may run commands and read the environment
 * @param xsltCacheSize the maximum number of compiled XSLT stylesheets kept for reuse
 */
@ConfigurationProperties("xproc.openapi")
public record OpenApiProperties(Path configDir, String description, String basePath,
    String descriptionPath, Boolean checkModified, Long maxRequestSize, Boolean allowFileAccess,
    Boolean allowNetworkAccess, Boolean allowOsAccess, Integer xsltCacheSize) {

  public OpenApiProperties {
    if (configDir == null) {
      throw new IllegalArgumentException("xproc.openapi.config-dir must be set");
    }
    configDir = configDir.toAbsolutePath().normalize();
    descriptionPath = descriptionPath == null ? "/openapi" : descriptionPath;
    checkModified = checkModified == null || checkModified;
    maxRequestSize = maxRequestSize == null ? 10L * 1024 * 1024 : maxRequestSize;
    allowFileAccess = allowFileAccess == null || allowFileAccess;
    allowNetworkAccess = allowNetworkAccess == null || allowNetworkAccess;
    allowOsAccess = allowOsAccess != null && allowOsAccess;
    xsltCacheSize = xsltCacheSize == null ? 100 : xsltCacheSize;
  }
}
