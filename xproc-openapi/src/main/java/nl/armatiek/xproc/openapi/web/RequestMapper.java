package nl.armatiek.xproc.openapi.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import net.sf.saxon.om.NameChecker;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.OptionSignature;
import nl.armatiek.xproc.PortSignature;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.openapi.OpenApiNs;
import nl.armatiek.xproc.openapi.OpenApiProperties;
import nl.armatiek.xproc.openapi.api.ApiDescription;
import nl.armatiek.xproc.openapi.api.OperationSpec;
import nl.armatiek.xproc.openapi.api.ParameterSpec;

/**
 * Turns an HTTP request into the inputs and options of a pipeline run:
 * <ul>
 * <li>the OpenAPI parameters of the operation become options in the namespace of their location
 * ({@link OpenApiNs#PATH}, {@link OpenApiNs#QUERY}, {@link OpenApiNs#HEADER},
 * {@link OpenApiNs#COOKIE});</li>
 * <li>general request information becomes options in the {@link OpenApiNs#REQUEST} namespace;</li>
 * <li>a request body becomes the document on the source port;</li>
 * <li>the files of a multipart request become documents on the port named after the part (or
 * the file name), its other parts options in the {@link OpenApiNs#PART} namespace.</li>
 * </ul>
 * Only options and ports the pipeline declares are passed. Option values are passed as
 * xs:untypedAtomic, so they are cast to the type the pipeline declares.
 */
@Component
public class RequestMapper {

  private static final Logger LOG = LoggerFactory.getLogger(RequestMapper.class);

  private final XProcProcessor processor;
  private final OpenApiProperties props;

  public RequestMapper(XProcProcessor processor, OpenApiProperties props) {
    this.processor = processor;
    this.props = props;
  }

  /** Builds the invocation of the pipeline for a request. */
  public XProcPipeline.Invocation map(HttpServletRequest request, ApiDescription api,
      ApiDescription.Match match, XProcPipeline pipeline) {
    Set<QName> options = pipeline.getOptions().stream().filter(o -> !o.isStatic())
        .map(OptionSignature::name).collect(Collectors.toSet());
    Set<String> ports = pipeline.getInputs().stream().map(PortSignature::name)
        .collect(Collectors.toSet());
    XProcPipeline.Invocation inv = pipeline.newInvocation();
    Map<String, List<String>> query = parseQuery(request.getQueryString());

    for (ParameterSpec p : match.operation().parameters()) {
      List<String> values = parameterValues(request, p, match.pathVariables(), query);
      if (values == null) {
        if (!p.defaultValues().isEmpty()) {
          values = p.defaultValues();
        } else if (p.required()) {
          throw new ApiException(HttpStatus.BAD_REQUEST, "The required " + p.in()
              + " parameter '" + p.name() + "' is missing");
        } else {
          continue;
        }
      }
      if (p.optionName() != null && options.contains(p.optionName())) {
        inv.withOption(p.optionName(), untyped(values));
      }
    }
    requestOptions(request, api, match, query, options, inv);

    if (isMultipart(request)) {
      multipart(request, options, ports, inv);
    } else if (hasBody(request)) {
      XProcDocument body = parse(readBody(request), request.getContentType(),
          URI.create(request.getRequestURL().toString()));
      if (ports.contains("source")) {
        inv.withInput("source", body);
      } else {
        LOG.debug("The pipeline of {} has no source port; the request body is ignored",
            match.operation().operationId());
      }
    }
    return inv;
  }

  /** The values of a parameter in the request, or null if it is absent. */
  private static List<String> parameterValues(HttpServletRequest request, ParameterSpec p,
      Map<String, String> pathVariables, Map<String, List<String>> query) {
    switch (p.in()) {
      case "path" -> {
        String v = pathVariables.get(p.name());
        return v == null ? null : p.split(v);
      }
      case "query" -> {
        List<String> given = query.get(p.name());
        if (given == null) {
          return null;
        }
        if (!p.array()) {
          return List.of(given.get(0));
        }
        List<String> values = new ArrayList<>();
        for (String v : given) {
          if (p.explode() && p.style().equals("form")) {
            values.add(v);
          } else {
            values.addAll(p.split(v));
          }
        }
        return values;
      }
      case "header" -> {
        List<String> given = Collections.list(request.getHeaders(p.name()));
        if (given.isEmpty()) {
          return null;
        }
        return p.array() ? given.stream().flatMap(v -> p.split(v).stream()).map(String::trim)
            .toList() : List.of(String.join(", ", given));
      }
      case "cookie" -> {
        if (request.getCookies() != null) {
          for (Cookie c : request.getCookies()) {
            if (c.getName().equals(p.name())) {
              return p.split(c.getValue());
            }
          }
        }
        return null;
      }
      default -> {
        return null;
      }
    }
  }

  private void requestOptions(HttpServletRequest request, ApiDescription api,
      ApiDescription.Match match, Map<String, List<String>> query, Set<QName> options,
      XProcPipeline.Invocation inv) {
    OperationSpec op = match.operation();
    Map<String, XdmValue> values = new LinkedHashMap<>();
    values.put("method", untyped(request.getMethod()));
    String url = request.getRequestURL().toString();
    values.put("uri", untyped(request.getQueryString() == null ? url
        : url + "?" + request.getQueryString()));
    values.put("path", untyped(path(request)));
    values.put("base-path", untyped(api.basePath()));
    values.put("path-template", untyped(op.pathTemplate()));
    values.put("operation-id", untyped(op.operationId()));
    values.put("query-string", untyped(request.getQueryString() == null ? ""
        : request.getQueryString()));
    values.put("scheme", untyped(request.getScheme()));
    values.put("server-name", untyped(request.getServerName()));
    values.put("server-port", untyped(Integer.toString(request.getServerPort())));
    values.put("remote-address", untyped(request.getRemoteAddr()));
    if (request.getRemoteUser() != null) {
      values.put("remote-user", untyped(request.getRemoteUser()));
    }
    if (request.getContentType() != null) {
      values.put("content-type", untyped(request.getContentType()));
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (String name : Collections.list(request.getHeaderNames())) {
      headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
          .addAll(Collections.list(request.getHeaders(name)));
    }
    values.put("headers", map(headers));
    values.put("query", map(query));
    Map<String, List<String>> cookies = new LinkedHashMap<>();
    if (request.getCookies() != null) {
      for (Cookie c : request.getCookies()) {
        cookies.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c.getValue());
      }
    }
    values.put("cookies", map(cookies));
    Map<String, List<String>> pathParams = new LinkedHashMap<>();
    match.pathVariables().forEach((k, v) -> pathParams.put(k, List.of(v)));
    values.put("path-parameters", map(pathParams));
    for (Map.Entry<String, XdmValue> e : values.entrySet()) {
      QName name = new QName(OpenApiNs.REQUEST, e.getKey());
      if (options.contains(name)) {
        inv.withOption(name, e.getValue());
      }
    }
  }

  private void multipart(HttpServletRequest request, Set<QName> options, Set<String> ports,
      XProcPipeline.Invocation inv) {
    Map<QName, List<String>> fields = new LinkedHashMap<>();
    try {
      for (Part part : request.getParts()) {
        String filename = part.getSubmittedFileName();
        if (filename == null) {
          String charset = charset(part.getContentType());
          String value = new String(part.getInputStream().readAllBytes(),
              Charset.forName(charset));
          if (NameChecker.isValidNCName(part.getName())) {
            QName name = new QName(OpenApiNs.PART, part.getName());
            if (options.contains(name)) {
              fields.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
          }
          continue;
        }
        String port = ports.contains(part.getName()) ? part.getName()
            : ports.contains(filename) ? filename : null;
        if (port == null) {
          LOG.debug("No input port for the file part '{}' ({}); it is ignored", part.getName(),
              filename);
          continue;
        }
        String contentType = part.getContentType();
        if (contentType == null || contentType.equals("application/octet-stream")) {
          contentType = MediaTypeFactory.getMediaType(filename)
              .map(org.springframework.http.MediaType::toString).orElse(contentType);
        }
        URI base = URI.create(request.getRequestURL().toString()).resolve(URLEncoder.encode(
            filename, StandardCharsets.UTF_8).replace("+", "%20"));
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
          bytes = in.readAllBytes();
        }
        inv.withInput(port, parse(bytes, contentType, base));
      }
    } catch (IOException | ServletException | IllegalStateException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read the multipart request: "
          + e.getMessage(), e);
    }
    fields.forEach((name, values) -> inv.withOption(name, untyped(values)));
  }

  private XProcDocument parse(byte[] bytes, String contentType, URI base) {
    MediaType ct = contentType == null ? MediaType.tryParse("application/octet-stream")
        : MediaType.tryParse(contentType);
    if (ct == null) {
      throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Invalid content type: "
          + contentType);
    }
    try {
      return processor.parseDocument(bytes, ct, base);
    } catch (XProcException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "The " + ct.toString()
          + " content cannot be parsed: " + e.getMessage(), e);
    }
  }

  private byte[] readBody(HttpServletRequest request) {
    long max = props.maxRequestSize();
    if (request.getContentLengthLong() > max) {
      throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "The request body is larger than "
          + max + " bytes");
    }
    try (InputStream in = request.getInputStream()) {
      byte[] bytes = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
      if (bytes.length > max) {
        throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "The request body is larger than "
            + max + " bytes");
      }
      return bytes;
    } catch (IOException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read the request body: "
          + e.getMessage(), e);
    }
  }

  private static boolean hasBody(HttpServletRequest request) {
    return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
  }

  private static boolean isMultipart(HttpServletRequest request) {
    String ct = request.getContentType();
    return ct != null && ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
  }

  /** The request path within the application (not decoded). */
  static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    return context != null && !context.isEmpty() && uri.startsWith(context)
        ? uri.substring(context.length()) : uri;
  }

  /** Parses a query string; names and values are decoded, repeated names keep all values. */
  static Map<String, List<String>> parseQuery(String queryString) {
    Map<String, List<String>> query = new LinkedHashMap<>();
    if (queryString == null || queryString.isEmpty()) {
      return query;
    }
    for (String pair : queryString.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String name = decode(eq < 0 ? pair : pair.substring(0, eq));
      String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
      query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
    return query;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid query string: " + e.getMessage());
    }
  }

  private static String charset(String contentType) {
    MediaType ct = contentType == null ? null : MediaType.tryParse(contentType);
    String cs = ct == null ? null : ct.getCharset();
    return cs != null && Charset.isSupported(cs) ? cs : "UTF-8";
  }

  private static XdmValue untyped(String value) {
    return untyped(List.of(value == null ? "" : value));
  }

  private static XdmValue untyped(List<String> values) {
    List<XdmAtomicValue> items = new ArrayList<>();
    for (String v : values) {
      items.add(untypedAtomic(v));
    }
    return new XdmValue(items);
  }

  private static XdmAtomicValue untypedAtomic(String v) {
    return (XdmAtomicValue) XdmValue.wrap(net.sf.saxon.value.StringValue.makeUntypedAtomic(
        net.sf.saxon.str.StringView.of(v)));
  }

  /** A map(xs:string, xs:string*). */
  private static XdmMap map(Map<String, List<String>> entries) {
    Map<XdmAtomicValue, XdmValue> map = new LinkedHashMap<>();
    entries.forEach((k, v) -> map.put(new XdmAtomicValue(k), new XdmValue(v.stream()
        .map(XdmAtomicValue::new).toList())));
    return XdmMap.makeMap(map);
  }
}
