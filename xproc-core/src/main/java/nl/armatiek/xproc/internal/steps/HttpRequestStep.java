package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.runtime.Executor;
import nl.armatiek.xproc.internal.util.Xdm;

/** p:http-request. */
final class HttpRequestStep implements StepImplementation {

  static final String HTTP_NS = "http://www.w3.org/ns/xproc-http";
  private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "HEAD",
      "PATCH", "OPTIONS", "TRACE");
  private static final List<String> BODY_METHODS = List.of("POST", "PUT", "PATCH");

  private enum Type {
    STRING, BOOLEAN, INTEGER
  }

  /** The required types of the standard keys of the parameters option. */
  private static final Map<String, Type> PARAMETER_TYPES = Map.ofEntries(
      Map.entry("http-version", Type.STRING),
      Map.entry("override-content-type", Type.STRING),
      Map.entry("override-content-encoding", Type.STRING),
      Map.entry("accept-multipart", Type.BOOLEAN),
      Map.entry("permit-expired-ssl-certificate", Type.BOOLEAN),
      Map.entry("permit-untrusted-ssl-certificate", Type.BOOLEAN),
      Map.entry("follow-redirect", Type.INTEGER),
      Map.entry("timeout", Type.INTEGER),
      Map.entry("fail-on-timeout", Type.BOOLEAN),
      Map.entry("status-only", Type.BOOLEAN),
      Map.entry("suppress-cookies", Type.BOOLEAN),
      Map.entry("send-body-anyway", Type.BOOLEAN));

  /** The required types of the standard keys of the auth option. */
  private static final Map<String, Type> AUTH_TYPES = Map.of(
      "username", Type.STRING,
      "password", Type.STRING,
      "auth-method", Type.STRING,
      "send-authorization", Type.BOOLEAN);

  private StepContext ctx;
  private Map<QName, XdmValue> params;

  @Override
  public void run(StepContext ctx) {
    this.ctx = ctx;
    params = Executor.toQNameMap(ctx.option("parameters"), ctx.optionNamespaces("parameters"));
    for (Map.Entry<QName, XdmValue> e : params.entrySet()) {
      Type type = e.getKey().getNamespace().isEmpty()
          ? PARAMETER_TYPES.get(e.getKey().getLocalName()) : null;
      if (type != null && !hasType(e.getValue(), type)) {
        throw XProcException.xc(124, "The value of parameter " + e.getKey().getLocalName()
            + " must be a single " + type.name().toLowerCase(Locale.ROOT));
      }
    }
    if (intParam("timeout", 0) < 0) {
      throw XProcException.xc(124, "The timeout parameter must not be negative");
    }
    URI href = URI.create(Xdm.string(ctx.option("href")));
    if (!href.isAbsolute() && ctx.baseUri() != null) {
      href = ctx.baseUri().resolve(href);
    }
    if (href.getRawFragment() != null) {
      String s = href.toString();
      href = URI.create(s.substring(0, s.indexOf('#')));
    }
    String scheme = href.getScheme() == null ? "" : href.getScheme().toLowerCase(Locale.ROOT);
    String method = Xdm.string(ctx.option("method"));
    method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
    if (!METHODS.contains(method)) {
      throw XProcException.xc(122, "Unsupported HTTP method: " + method);
    }
    if ("file".equals(scheme)) {
      fileRequest(href, method);
      return;
    }
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw XProcException.xc(128, "Unsupported URI scheme: " + scheme);
    }
    ctx.pc().checkAccess(href);

    // headers
    Map<String, String> headers = new LinkedHashMap<>();
    XdmValue hv = ctx.option("headers");
    if (hv.size() > 0) {
      for (Map.Entry<XdmAtomicValue, XdmValue> e : ((XdmMap) hv.itemAt(0)).entrySet()) {
        String name = e.getKey().getStringValue();
        if (findHeader(headers, name) != null) {
          throw XProcException.xc(127, "Duplicate header: " + name);
        }
        headers.put(name, e.getValue().itemAt(0).getStringValue());
      }
      String ctKey = findHeader(headers, "content-type");
      if (ctKey != null && MediaType.tryParse(headers.get(ctKey)) == null) {
        throw XProcException.xd(79, "Invalid content-type header: " + headers.get(ctKey));
      }
    }
    List<XProcDocument> sources = ctx.inputs("source");
    if (sources.size() == 1) {
      for (Map.Entry<QName, XdmValue> e : sources.get(0).getProperties().entrySet()) {
        if (HTTP_NS.equals(e.getKey().getNamespace())
            && findHeader(headers, e.getKey().getLocalName()) == null) {
          headers.put(e.getKey().getLocalName(), Xdm.string(e.getValue()));
        }
      }
    }
    // auth
    Map<String, XdmValue> auth = new LinkedHashMap<>();
    XdmValue av = ctx.option("auth");
    if (av.size() > 0) {
      for (Map.Entry<XdmAtomicValue, XdmValue> e : ((XdmMap) av.itemAt(0)).entrySet()) {
        String key = e.getKey().getStringValue();
        Type type = AUTH_TYPES.get(key);
        if (type != null && !hasType(e.getValue(), type)) {
          throw XProcException.xc(123, "The value of auth key " + key + " must be a single "
              + type.name().toLowerCase(Locale.ROOT));
        }
        auth.put(key, e.getValue());
      }
      String existing = findHeader(headers, "authorization");
      if (existing != null) {
        headers.remove(existing);
      }
    }
    String authMethod = auth.containsKey("auth-method")
        ? Xdm.string(auth.get("auth-method")) : null;
    if ((auth.containsKey("username") || auth.containsKey("password")) && authMethod == null) {
      throw XProcException.xc(3, "auth-method is required with username/password");
    }
    if (authMethod != null && !authMethod.equalsIgnoreCase("basic")
        && !authMethod.equalsIgnoreCase("digest")) {
      throw XProcException.xc(3, "Unsupported authentication method: " + authMethod);
    }
    // body
    byte[] body = null;
    boolean sendBody = BODY_METHODS.contains(method) || boolParam("send-body-anyway");
    if (sendBody && !sources.isEmpty()) {
      body = buildBody(sources, headers);
    }
    // client
    long timeout = intParam("timeout", -1);
    long followRedirect = intParam("follow-redirect", -1);
    HttpClient client = buildClient(scheme);

    String username = Xdm.string(auth.get("username"));
    String password = Xdm.string(auth.get("password"));
    boolean sendAuth = auth.containsKey("send-authorization")
        && Xdm.bool(auth.get("send-authorization"));
    if (authMethod != null && authMethod.equalsIgnoreCase("basic") && sendAuth) {
      headers.put("Authorization", basic(username, password));
    }

    URI current = href;
    String currentMethod = method;
    byte[] currentBody = body;
    HttpResponse<byte[]> resp;
    int redirects = 0;
    boolean authTried = sendAuth && authMethod != null && authMethod.equalsIgnoreCase("basic");
    boolean timedOut = false;
    while (true) {
      try {
        resp = send(client, current, currentMethod, headers, currentBody, timeout);
      } catch (HttpTimeoutException e) {
        timedOut = true;
        resp = null;
        break;
      }
      int sc = resp.statusCode();
      if (sc == 401 && authMethod != null && !authTried) {
        authTried = true;
        String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
        if (authMethod.equalsIgnoreCase("basic")) {
          headers.put("Authorization", basic(username, password));
        } else {
          if (!challenge.toLowerCase(Locale.ROOT).startsWith("digest")) {
            throw XProcException.xc(3, "Unsupported authentication challenge: " + challenge);
          }
          headers.put("Authorization", digest(challenge, username, password, currentMethod,
              current));
        }
        continue;
      }
      if ((sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308)
          && resp.headers().firstValue("Location").isPresent()
          && (followRedirect < 0 || redirects < followRedirect)) {
        redirects++;
        if (redirects > 50) {
          throw XProcException.xd(30, "Too many redirects");
        }
        current = current.resolve(resp.headers().firstValue("Location").get());
        if (sc == 303 || ((sc == 301 || sc == 302) && "POST".equals(currentMethod))) {
          currentMethod = "GET";
          currentBody = null;
        }
        continue;
      }
      break;
    }

    int status = timedOut ? 408 : resp.statusCode();
    Map<String, String> respHeaders = new TreeMap<>();
    if (resp != null && !boolParam("status-only")) {
      for (Map.Entry<String, List<String>> e : resp.headers().map().entrySet()) {
        if (e.getKey().startsWith(":")) {
          continue;
        }
        respHeaders.put(e.getKey().toLowerCase(Locale.ROOT), String.join(", ", e.getValue()));
      }
    }
    Map<XdmAtomicValue, XdmValue> report = new LinkedHashMap<>();
    report.put(new XdmAtomicValue("status-code"), new XdmAtomicValue(status));
    report.put(new XdmAtomicValue("base-uri"), new XdmAtomicValue(resp == null ? current
        : resp.uri()));
    Map<XdmAtomicValue, XdmValue> hm = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : respHeaders.entrySet()) {
      hm.put(new XdmAtomicValue(e.getKey()), new XdmAtomicValue(e.getValue()));
    }
    report.put(new XdmAtomicValue("headers"), new XdmMap(hm));
    XdmMap reportMap = new XdmMap(report);

    if (status == 408 && boolParam("fail-on-timeout")) {
      throw XProcException.xc(78, "The HTTP request timed out");
    }
    String assertion = Xdm.string(ctx.option("assert"));
    if (assertion != null && !assertion.isBlank()) {
      var exec = ctx.pc().xpath.compileForStep(assertion, ctx.optionNamespaces("assert"),
          ctx.baseUri(), List.of(), false);
      XdmValue r = StepSupport.evaluate(exec, reportMap, null);
      if (!MiscSteps.ebv(ctx, r)) {
        throw XProcException.xc(126, "HTTP response assertion failed: " + assertion
            + " (status " + status + ")");
      }
    }
    ctx.output("report", XProcDocument.of(ctx.processor(), reportMap, MediaType.JSON,
        (URI) null));
    if (resp == null) {
      return;
    }
    if (boolParam("status-only")) {
      String ct = resp.headers().firstValue("Content-Type").orElse("");
      if (ct.toLowerCase(Locale.ROOT).startsWith("multipart/") && rejectMultipart()) {
        throw XProcException.xc(125, "Multipart response received");
      }
      return;
    }
    byte[] data = resp.body();
    if (data == null || data.length == 0) {
      return;
    }
    String encoding = paramString("override-content-encoding");
    if (encoding == null) {
      encoding = respHeaders.get("content-encoding");
    } else if (!List.of("gzip", "deflate", "identity", "x-gzip").contains(encoding)) {
      throw XProcException.xc(132, "Unsupported content encoding: " + encoding);
    }
    data = decode(data, encoding);
    String ctHeader = respHeaders.get("content-type");
    String override = paramString("override-content-type");
    MediaType ct = MediaType.parse(override != null ? override
        : ctHeader == null ? "application/octet-stream" : ctHeader);
    URI base = resp.uri();
    if (ct.getType().equals("multipart")) {
      if (rejectMultipart()) {
        throw XProcException.xc(125, "Multipart response received");
      }
      for (XProcDocument d : parseMultipart(data, ct, base)) {
        ctx.output("result", d);
      }
      return;
    }
    try {
      MediaType reported = ctHeader == null ? null : MediaType.tryParse(ctHeader);
      var charset = ct.getCharset(reported == null ? null : reported.getCharset(null));
      // The charset only describes the encoding of the response body; once the body has been
      // parsed into an XML, HTML or JSON document it no longer applies
      MediaType docType = ct.isMarkup() || ct.isJson() ? withoutCharset(ct) : ct;
      ctx.output("result", ctx.pc().loader.parse(data, docType, charset, base, null,
          Map.of(Ns.BASE_URI, new XdmAtomicValue(base)), true));
    } catch (XProcException e) {
      if (override != null) {
        throw XProcException.xc(30, "Cannot interpret the response as " + override + ": "
            + e.getMessage());
      }
      throw e;
    }
  }

  private void fileRequest(URI href, String method) {
    if (!"GET".equals(method)) {
      throw XProcException.xc(122, "Only GET is supported for file: URIs");
    }
    var res = ctx.pc().loader.read(href);
    String ct = paramString("override-content-type");
    if (ct == null) {
      ct = nl.armatiek.xproc.internal.io.DocumentLoader.contentTypeForName(href.getPath());
    }
    MediaType mt = MediaType.parse(ct == null ? "application/octet-stream" : ct);
    Map<XdmAtomicValue, XdmValue> report = new LinkedHashMap<>();
    report.put(new XdmAtomicValue("status-code"), new XdmAtomicValue(200));
    report.put(new XdmAtomicValue("base-uri"), new XdmAtomicValue(href));
    report.put(new XdmAtomicValue("headers"), new XdmMap());
    ctx.output("report", XProcDocument.of(ctx.processor(), new XdmMap(report), MediaType.JSON,
        (URI) null));
    ctx.output("result", ctx.pc().loader.parse(res.bytes(), mt, mt.getCharset(null), href,
        null, Map.of(Ns.BASE_URI, new XdmAtomicValue(href)), true));
  }

  private byte[] buildBody(List<XProcDocument> sources, Map<String, String> headers) {
    String ctKey = findHeader(headers, "content-type");
    String ctHeader = ctKey == null ? null : headers.get(ctKey);
    MediaType ct = ctHeader == null ? null : MediaType.parse(ctHeader);
    Map<QName, XdmValue> ser = DocumentSerializer.toParams(ctx.option("serialization"));
    boolean multipart = sources.size() > 1 || (ct != null && "multipart".equals(ct.getType()));
    if (sources.size() > 1 && ct != null && !"multipart".equals(ct.getType())) {
      throw XProcException.xc(133, "A multipart content type is required for multiple "
          + "documents");
    }
    if (!multipart) {
      XProcDocument d = sources.get(0);
      if (ctKey == null) {
        headers.put("Content-Type", contentTypeOf(d, ser).toString());
      }
      return ctx.pc().serializer.serializeToBytes(d, ser);
    }
    if (ct == null) {
      ct = MediaType.parse("multipart/mixed");
    }
    String boundary = ct.getParameter("boundary");
    if (boundary == null) {
      boundary = "boundary-" + UUID.randomUUID();
      ct = ct.withParameter("boundary", boundary);
    } else if (boundary.startsWith("--") || boundary.isEmpty() || boundary.length() > 70) {
      throw XProcException.xc(203, "Invalid multipart boundary: " + boundary);
    }
    if (ctKey != null) {
      headers.remove(ctKey);
    }
    headers.put("Content-Type", ct.toString());
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      for (XProcDocument d : sources) {
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
        Map<String, String> partHeaders = new LinkedHashMap<>();
        partHeaders.put("Content-Type", contentTypeOf(d, ser).toString());
        for (Map.Entry<QName, XdmValue> e : d.getProperties().entrySet()) {
          if (HTTP_NS.equals(e.getKey().getNamespace())) {
            String existing = findHeader(partHeaders, e.getKey().getLocalName());
            if (existing != null) {
              partHeaders.remove(existing);
            }
            partHeaders.put(e.getKey().getLocalName(), Xdm.string(e.getValue()));
          }
        }
        for (Map.Entry<String, String> e : partHeaders.entrySet()) {
          bos.write((e.getKey() + ": " + e.getValue() + "\r\n").getBytes(
              StandardCharsets.UTF_8));
        }
        bos.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        bos.write(ctx.pc().serializer.serializeToBytes(d, ser));
        bos.write("\r\n".getBytes(StandardCharsets.US_ASCII));
      }
      bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    } catch (IOException e) {
      throw XProcException.xd(30, e.getMessage());
    }
    return bos.toByteArray();
  }

  /**
   * The content type to send a document with: its own content type, with the charset of its
   * serialization encoding for non-binary documents.
   */
  private static MediaType contentTypeOf(XProcDocument d, Map<QName, XdmValue> ser) {
    MediaType ct = d.getContentType();
    XdmValue enc = DocumentSerializer.merge(ser, d).get(new QName("encoding"));
    if (enc != null && !d.isBinary() && ct.getCharset() == null) {
      ct = ct.withParameter("charset", enc.itemAt(0).getStringValue());
    }
    return ct;
  }

  private static MediaType withoutCharset(MediaType ct) {
    if (ct.getCharset() == null) {
      return ct;
    }
    MediaType result = ct.withoutParameters();
    for (Map.Entry<String, String> p : ct.getParameters().entrySet()) {
      if (!p.getKey().equalsIgnoreCase("charset")) {
        result = result.withParameter(p.getKey(), p.getValue());
      }
    }
    return result;
  }

  private HttpClient buildClient(String scheme) {
    HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(60));
    if (!boolParam("suppress-cookies")) {
      b.cookieHandler(new CookieManager());
    }
    String version = paramString("http-version");
    if (version != null) {
      b.version(version.startsWith("2") ? HttpClient.Version.HTTP_2
          : HttpClient.Version.HTTP_1_1);
    } else if ("http".equals(scheme)) {
      // Plain HTTP/2 (h2c) would add upgrade headers to every request
      b.version(HttpClient.Version.HTTP_1_1);
    }
    if (boolParam("permit-untrusted-ssl-certificate")
        || boolParam("permit-expired-ssl-certificate")) {
      try {
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[] {new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        }}, new SecureRandom());
        b.sslContext(ssl);
      } catch (Exception e) {
        throw XProcException.xd(30, "Cannot configure SSL: " + e.getMessage());
      }
    }
    return b.build();
  }

  private HttpResponse<byte[]> send(HttpClient client, URI uri, String method,
      Map<String, String> headers, byte[] body, long timeout) throws HttpTimeoutException {
    HttpRequest.Builder rb = HttpRequest.newBuilder(uri);
    if (timeout >= 0) {
      rb.timeout(Duration.ofSeconds(Math.max(1, timeout)));
    }
    for (Map.Entry<String, String> e : headers.entrySet()) {
      String n = e.getKey().toLowerCase(Locale.ROOT);
      if (n.equals("content-length") || n.equals("host") || n.equals("connection")
          || n.equals("transfer-encoding")) {
        if (n.equals("transfer-encoding") && !"chunked".equalsIgnoreCase(e.getValue())) {
          throw XProcException.xc(131, "Unsupported transfer encoding: " + e.getValue());
        }
        continue;
      }
      try {
        rb.header(e.getKey(), e.getValue());
      } catch (IllegalArgumentException ex) {
        throw XProcException.xd(30, "Invalid header " + e.getKey() + ": " + ex.getMessage());
      }
    }
    if (body == null && method.equals("GET")) {
      rb.GET();
    } else if (body == null && method.equals("DELETE")) {
      rb.DELETE();
    } else {
      rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofByteArray(body));
    }
    try {
      return client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpTimeoutException e) {
      throw e;
    } catch (IOException e) {
      throw XProcException.xd(11, "HTTP request to " + uri + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw XProcException.xd(30, "Interrupted");
    }
  }

  private static byte[] decode(byte[] data, String encoding) {
    if (encoding == null || encoding.equals("identity")) {
      return data;
    }
    try {
      if (encoding.contains("gzip")) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
          return in.readAllBytes();
        }
      }
      if (encoding.contains("deflate")) {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
          return in.readAllBytes();
        }
      }
    } catch (IOException e) {
      throw XProcException.xc(132, "Cannot decode content encoding " + encoding);
    }
    return data;
  }

  private List<XProcDocument> parseMultipart(byte[] data, MediaType ct, URI base) {
    String boundary = ct.getParameter("boundary");
    if (boundary == null) {
      throw XProcException.xc(30, "Multipart response without boundary");
    }
    byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
    List<XProcDocument> docs = new ArrayList<>();
    int pos = indexOf(data, delim, 0);
    while (pos >= 0) {
      int start = pos + delim.length;
      if (start + 1 < data.length && data[start] == '-' && data[start + 1] == '-') {
        break;
      }
      // skip line end
      while (start < data.length && (data[start] == '\r' || data[start] == '\n')) {
        start++;
        if (data[start - 1] == '\n') {
          break;
        }
      }
      int next = indexOf(data, delim, start);
      if (next < 0) {
        break;
      }
      int end = next;
      if (end >= 2 && data[end - 2] == '\r' && data[end - 1] == '\n') {
        end -= 2;
      } else if (end >= 1 && data[end - 1] == '\n') {
        end -= 1;
      }
      byte[] part = java.util.Arrays.copyOfRange(data, start, end);
      docs.add(parsePart(part, base));
      pos = next;
    }
    return docs;
  }

  private XProcDocument parsePart(byte[] part, URI base) {
    int split = indexOf(part, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
    int bodyStart;
    if (split >= 0) {
      bodyStart = split + 4;
    } else {
      split = indexOf(part, "\n\n".getBytes(StandardCharsets.ISO_8859_1), 0);
      bodyStart = split < 0 ? 0 : split + 2;
      if (split < 0) {
        split = 0;
      }
    }
    String headerText = new String(part, 0, split, StandardCharsets.UTF_8);
    Map<QName, XdmValue> props = new LinkedHashMap<>();
    props.put(Ns.BASE_URI, new XdmAtomicValue(base));
    String ct = "text/plain";
    for (String line : headerText.split("\r?\n")) {
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if (name.equals("content-type")) {
        ct = value;
      } else {
        props.put(new QName(HTTP_NS, name), new XdmAtomicValue(value));
      }
    }
    byte[] body = java.util.Arrays.copyOfRange(part, bodyStart, part.length);
    MediaType mt = MediaType.parse(ct);
    return ctx.pc().loader.parse(body, mt, mt.getCharset(null), base, null, props, true);
  }

  private static int indexOf(byte[] data, byte[] pattern, int from) {
    outer:
    for (int i = from; i <= data.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) {
        if (data[i + j] != pattern[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static String findHeader(Map<String, String> headers, String name) {
    for (String k : headers.keySet()) {
      if (k.equalsIgnoreCase(name)) {
        return k;
      }
    }
    return null;
  }

  private static String basic(String user, String password) {
    String s = (user == null ? "" : user) + ":" + (password == null ? "" : password);
    return "Basic " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String digest(String challenge, String user, String password, String method,
      URI uri) {
    Map<String, String> p = new LinkedHashMap<>();
    Matcher m = Pattern.compile("(\\w+)=(\"([^\"]*)\"|([^,\\s]*))").matcher(challenge
        .substring(6));
    while (m.find()) {
      p.put(m.group(1).toLowerCase(Locale.ROOT), m.group(3) != null ? m.group(3) : m.group(4));
    }
    String realm = p.getOrDefault("realm", "");
    String nonce = p.getOrDefault("nonce", "");
    String qop = p.get("qop");
    String algorithm = p.getOrDefault("algorithm", "MD5");
    if (!algorithm.equalsIgnoreCase("MD5")) {
      throw XProcException.xc(3, "Unsupported digest algorithm: " + algorithm);
    }
    String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    String ha1 = md5(user + ":" + realm + ":" + password);
    String ha2 = md5(method + ":" + path);
    String cnonce = Long.toHexString(new SecureRandom().nextLong());
    String nc = "00000001";
    StringBuilder sb = new StringBuilder("Digest ");
    sb.append("username=\"").append(user).append("\", realm=\"").append(realm)
        .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(path).append('"');
    if (qop != null && qop.contains("auth")) {
      String response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
      sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce)
          .append("\", response=\"").append(response).append('"');
    } else {
      sb.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
    }
    if (p.containsKey("opaque")) {
      sb.append(", opaque=\"").append(p.get("opaque")).append('"');
    }
    sb.append(", algorithm=MD5");
    return sb.toString();
  }

  private static String md5(String s) {
    try {
      return XmlSteps.hex(MessageDigest.getInstance("MD5").digest(s.getBytes(
          StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean boolParam(String name) {
    XdmValue v = params.get(new QName(name));
    if (v == null || v.size() == 0) {
      return false;
    }
    return Xdm.bool(v);
  }

  private boolean rejectMultipart() {
    return params.containsKey(new QName("accept-multipart")) && !boolParam("accept-multipart");
  }

  /**
   * True if the value is a single atomic value of the type. Untyped atomic values are accepted
   * when they can be cast to the type.
   */
  private static boolean hasType(XdmValue value, Type type) {
    if (value.size() != 1 || !(value.itemAt(0) instanceof XdmAtomicValue av)) {
      return false;
    }
    if (av.getUnderlyingValue() instanceof net.sf.saxon.value.StringValue sv
        && sv.getItemType() == net.sf.saxon.type.BuiltInAtomicType.UNTYPED_ATOMIC) {
      String s = av.getStringValue().trim();
      return switch (type) {
        case STRING -> true;
        case BOOLEAN -> List.of("true", "false", "1", "0").contains(s);
        case INTEGER -> s.matches("[+-]?[0-9]+");
      };
    }
    ItemType required = switch (type) {
      case STRING -> ItemType.STRING;
      case BOOLEAN -> ItemType.BOOLEAN;
      case INTEGER -> ItemType.INTEGER;
    };
    return required.matches(av);
  }

  private long intParam(String name, long dflt) {
    XdmValue v = params.get(new QName(name));
    if (v == null || v.size() == 0) {
      return dflt;
    }
    try {
      return Long.parseLong(v.itemAt(0).getStringValue().trim());
    } catch (NumberFormatException e) {
      throw XProcException.xc(124, "Parameter " + name + " must be an integer");
    }
  }

  private String paramString(String name) {
    XdmValue v = params.get(new QName(name));
    return v == null || v.size() == 0 ? null : v.itemAt(0).getStringValue();
  }
}
