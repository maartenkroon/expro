package nl.armatiek.xproc.internal.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.transform.sax.SAXSource;

import org.xml.sax.InputSource;

import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.ProcessorContext;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.sax.HtmlParser;

/** Loads documents from URIs and parses representations (XML, HTML, text, JSON, binary). */
public final class DocumentLoader {

  private static final Map<String, String> EXTENSIONS = new LinkedHashMap<>();

  static {
    String[][] table = {
      {"xml", "application/xml"}, {"xpl", "application/xml"}, {"xsd", "application/xml"},
      {"xsl", "application/xslt+xml"}, {"xslt", "application/xslt+xml"},
      {"rng", "application/xml"}, {"sch", "application/xml"}, {"svg", "image/svg+xml"},
      {"xhtml", "application/xhtml+xml"}, {"html", "text/html"}, {"htm", "text/html"},
      {"txt", "text/plain"}, {"text", "text/plain"}, {"csv", "text/csv"},
      {"css", "text/css"}, {"js", "application/javascript"}, {"json", "application/json"},
      {"xq", "application/xquery"}, {"xqy", "application/xquery"},
      {"xquery", "application/xquery"}, {"rnc", "application/relax-ng-compact-syntax"},
      {"md", "text/markdown"}, {"dtd", "application/xml-dtd"}, {"ent", "text/plain"},
      {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"},
      {"gif", "image/gif"}, {"pdf", "application/pdf"}, {"zip", "application/zip"},
      {"jar", "application/java-archive"}, {"gz", "application/gzip"},
      {"epub", "application/epub+zip"}, {"docx",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
      {"tar", "application/x-tar"}, {"nvdl", "application/xml"}, {"xpr", "application/xml"},
      {"yaml", "application/x-yaml"}, {"yml", "application/x-yaml"}
    };
    for (String[] e : table) {
      EXTENSIONS.put(e[0], e[1]);
    }
  }

  private final ProcessorContext pc;
  private HttpClient httpClient;

  public DocumentLoader(ProcessorContext pc) {
    this.pc = pc;
  }

  /** Guesses a content type from a file name; null if unknown. */
  public static String contentTypeForName(String name) {
    if (name == null) {
      return null;
    }
    String n = name;
    int q = n.indexOf('?');
    if (q >= 0) {
      n = n.substring(0, q);
    }
    int dot = n.lastIndexOf('.');
    int slash = n.lastIndexOf('/');
    if (dot < 0 || dot < slash) {
      return null;
    }
    return EXTENSIONS.get(n.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /** A raw resource: bytes plus the content type reported by the protocol (may be null). */
  public record Resource(URI uri, byte[] bytes, String reportedContentType) {
  }

  /** Reads the bytes of a resource. Raises err:XD0011 if it cannot be read. */
  public Resource read(URI href) {
    URI uri = pc.mapUri(href);
    pc.checkAccess(uri);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    try {
      switch (scheme) {
        case "file" -> {
          Path path = Path.of(uri);
          return new Resource(href, Files.readAllBytes(path), null);
        }
        case "http", "https" -> {
          HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
          HttpResponse<byte[]> resp = httpClient().send(req, HttpResponse.BodyHandlers
              .ofByteArray());
          if (resp.statusCode() >= 400) {
            throw XProcException.xd(11, "Cannot load " + href + ": HTTP status "
                + resp.statusCode());
          }
          String ct = resp.headers().firstValue("Content-Type").orElse(null);
          return new Resource(resp.uri(), resp.body(), ct);
        }
        case "data" -> {
          return readDataUri(uri);
        }
        case "jar", "classpath" -> {
          URLConnection conn;
          if ("classpath".equals(scheme)) {
            String p = uri.getSchemeSpecificPart();
            while (p.startsWith("/")) {
              p = p.substring(1);
            }
            var url = Thread.currentThread().getContextClassLoader().getResource(p);
            if (url == null) {
              throw XProcException.xd(11, "Resource not found: " + href);
            }
            conn = url.openConnection();
          } else {
            conn = uri.toURL().openConnection();
          }
          try (InputStream in = conn.getInputStream()) {
            return new Resource(href, in.readAllBytes(), null);
          }
        }
        default -> throw XProcException.xd(12, "Unsupported URI scheme: " + href);
      }
    } catch (NoSuchFileException e) {
      throw XProcException.xd(11, "Resource does not exist: " + href, e);
    } catch (IOException e) {
      throw XProcException.xd(11, "Cannot read " + href + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw XProcException.xd(11, "Interrupted while reading " + href, e);
    } catch (IllegalArgumentException e) {
      throw XProcException.xd(11, "Cannot read " + href + ": " + e.getMessage(), e);
    }
  }

  private Resource readDataUri(URI uri) {
    String ssp = uri.getRawSchemeSpecificPart();
    int comma = ssp.indexOf(',');
    if (comma < 0) {
      throw XProcException.xd(11, "Invalid data: URI");
    }
    String meta = ssp.substring(0, comma);
    String data = ssp.substring(comma + 1);
    boolean base64 = meta.endsWith(";base64");
    if (base64) {
      meta = meta.substring(0, meta.length() - 7);
    }
    byte[] bytes = base64 ? java.util.Base64.getMimeDecoder().decode(data)
        : java.net.URLDecoder.decode(data, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    return new Resource(uri, bytes, meta.isEmpty() ? "text/plain" : meta);
  }

  public synchronized HttpClient httpClient() {
    if (httpClient == null) {
      httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(60)).build();
    }
    return httpClient;
  }

  /**
   * Implements the semantics of p:load / p:document.
   *
   * @param href the absolute URI
   * @param contentType the explicitly specified content type (may be null)
   * @param params the parameters (may be null)
   * @param docProps document properties (may be null)
   */
  public XProcDocument load(URI href, String contentType, Map<QName, XdmValue> params,
      Map<QName, XdmValue> docProps) {
    if (!href.isAbsolute()) {
      throw XProcException.xd(64, "Base URI is not absolute: " + href);
    }
    MediaType explicit = contentType == null ? null : MediaType.parse(contentType);
    if (docProps != null && docProps.containsKey(Ns.CONTENT_TYPE)) {
      MediaType propType = MediaType.parse(docProps.get(Ns.CONTENT_TYPE).itemAt(0)
          .getStringValue());
      if (explicit != null && !explicit.toBaseString().equals(propType.toBaseString())) {
        throw XProcException.xd(62, "Content type '" + contentType
            + "' differs from the content-type document property '" + propType + "'");
      }
      if (explicit == null) {
        explicit = propType;
      }
    }
    Resource res = read(href);
    MediaType ct = explicit;
    MediaType reported = res.reportedContentType() == null ? null
        : MediaType.tryParse(res.reportedContentType());
    if (ct == null) {
      ct = reported;
    }
    if (ct == null) {
      String guess = contentTypeForName(href.getPath());
      ct = guess == null ? MediaType.OCTET_STREAM : MediaType.parse(guess);
    }
    Charset charset = null;
    if (reported != null && reported.getCharset() != null) {
      charset = charsetOf(reported.getCharset(), 60);
    } else if (ct.getCharset() != null) {
      charset = charsetOf(ct.getCharset(), 60);
    }
    Map<QName, XdmValue> props = new LinkedHashMap<>();
    if (docProps != null) {
      props.putAll(docProps);
    }
    URI base = res.uri();
    if (!props.containsKey(Ns.BASE_URI)) {
      props.put(Ns.BASE_URI, new XdmAtomicValue(base));
    }
    props.remove(Ns.CONTENT_TYPE);
    return parse(res.bytes(), ct, charset, base, params, props, true);
  }

  /** Parses bytes according to a content type. */
  public XProcDocument parse(byte[] bytes, MediaType ct, Charset charset, URI base,
      Map<QName, XdmValue> params, Map<QName, XdmValue> props, boolean fromLoad) {
    return switch (ct.getKind()) {
      case XML -> XProcDocument.of(pc.processor, parseXml(bytes, base, params, charset), ct,
          props);
      case HTML -> XProcDocument.of(pc.processor, parseHtml(bytes, base, charset), ct, props);
      case TEXT -> {
        String text = decodeText(bytes, charset);
        XProcDocument d = XProcDocument.ofText(pc.processor, text, ct, base);
        yield d.withProperties(pc.processor, merge(d.getProperties(), props));
      }
      case JSON -> {
        String text = decodeText(bytes, charset);
        XdmValue v = parseJson(text, params);
        yield XProcDocument.of(pc.processor, v, ct, props);
      }
      default -> XProcDocument.ofBinary(pc.processor, bytes, ct, props);
    };
  }

  private static Map<QName, XdmValue> merge(Map<QName, XdmValue> a, Map<QName, XdmValue> b) {
    Map<QName, XdmValue> m = new LinkedHashMap<>(a);
    m.putAll(b);
    return m;
  }

  public static Charset charsetOf(String name, int errorNumber) {
    try {
      return Charset.forName(name);
    } catch (Exception e) {
      throw XProcException.xd(errorNumber, "Unsupported character set: " + name);
    }
  }

  /** Decodes text, handling byte order marks. */
  public static String decodeText(byte[] bytes, Charset charset) {
    int offset = 0;
    Charset cs = charset;
    if (cs == null) {
      if (bytes.length >= 2 && ((bytes[0] & 0xff) == 0xFE && (bytes[1] & 0xff) == 0xFF
          || (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xFE)) {
        cs = StandardCharsets.UTF_16;
      } else {
        cs = StandardCharsets.UTF_8;
      }
    }
    String name = cs.name().toUpperCase(Locale.ROOT);
    if (name.equals("UTF-8") && bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF
        && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {
      offset = 3;
    } else if (name.equals("UTF-16LE") && bytes.length >= 2 && (bytes[0] & 0xff) == 0xFF
        && (bytes[1] & 0xff) == 0xFE) {
      offset = 2;
    } else if (name.equals("UTF-16BE") && bytes.length >= 2 && (bytes[0] & 0xff) == 0xFE
        && (bytes[1] & 0xff) == 0xFF) {
      offset = 2;
    }
    // Java's UTF-16 decoder handles (and removes) the BOM itself.
    return new String(bytes, offset, bytes.length - offset, cs);
  }

  public XdmNode parseXml(byte[] bytes, URI base, Map<QName, XdmValue> params, Charset charset) {
    boolean dtdValidate = booleanParam(params, "dtd-validate");
    DocumentBuilder db = pc.processor.newDocumentBuilder();
    db.setLineNumbering(true);
    db.setDTDValidation(dtdValidate);
    if (base != null) {
      db.setBaseURI(base);
    }
    InputSource is = new InputSource(new ByteArrayInputStream(bytes));
    if (charset != null) {
      is.setEncoding(charset.name());
    }
    if (base != null) {
      is.setSystemId(base.toString());
    }
    try {
      return db.build(new SAXSource(is));
    } catch (SaxonApiException e) {
      if (dtdValidate) {
        throw XProcException.xd(23, "DTD validation failed: " + e.getMessage(), e);
      }
      throw XProcException.xd(49, "Not a well-formed XML document"
          + (base == null ? "" : " (" + base + ")") + ": " + e.getMessage(), e);
    }
  }

  /** Parses XML from a string. */
  public XdmNode parseXml(String text, URI base) {
    DocumentBuilder db = pc.processor.newDocumentBuilder();
    db.setLineNumbering(true);
    if (base != null) {
      db.setBaseURI(base);
    }
    InputSource is = new InputSource(new java.io.StringReader(text));
    if (base != null) {
      is.setSystemId(base.toString());
    }
    try {
      return db.build(new SAXSource(is));
    } catch (SaxonApiException e) {
      throw XProcException.xd(49, "Not a well-formed XML document: " + e.getMessage(), e);
    }
  }

  public XdmNode parseHtml(byte[] bytes, URI base, Charset charset) {
    InputSource is = new InputSource(new ByteArrayInputStream(bytes));
    if (charset != null) {
      is.setEncoding(charset.name());
    }
    return parseHtml(is, base);
  }

  public XdmNode parseHtml(String text, URI base) {
    return parseHtml(new InputSource(new java.io.StringReader(text)), base);
  }

  private XdmNode parseHtml(InputSource is, URI base) {
    HtmlParser parser = new HtmlParser(XmlViolationPolicy.ALTER_INFOSET);
    parser.setMappingLangToXmlLang(false);
    if (base != null) {
      is.setSystemId(base.toString());
    }
    DocumentBuilder db = pc.processor.newDocumentBuilder();
    if (base != null) {
      db.setBaseURI(base);
    }
    org.xml.sax.helpers.XMLFilterImpl quiet = new org.xml.sax.helpers.XMLFilterImpl(parser) {
      @Override
      public void setErrorHandler(org.xml.sax.ErrorHandler handler) {
        // HTML parse errors are recovered from; only report fatal errors
        super.setErrorHandler(new org.xml.sax.ErrorHandler() {
          @Override
          public void warning(org.xml.sax.SAXParseException e) {
          }

          @Override
          public void error(org.xml.sax.SAXParseException e) {
          }

          @Override
          public void fatalError(org.xml.sax.SAXParseException e)
              throws org.xml.sax.SAXException {
            handler.fatalError(e);
          }
        });
      }
    };
    try {
      return db.build(new SAXSource(quiet, is));
    } catch (SaxonApiException e) {
      throw XProcException.xd(78, "Cannot parse HTML document: " + e.getMessage(), e);
    }
  }

  /** Parses JSON using fn:parse-json() and the given parameters as options. */
  public XdmValue parseJson(String text, Map<QName, XdmValue> params) {
    Map<XdmAtomicValue, XdmValue> opts = new LinkedHashMap<>();
    if (params != null) {
      for (Map.Entry<QName, XdmValue> e : params.entrySet()) {
        if (e.getKey().getNamespace().isEmpty()) {
          switch (e.getKey().getLocalName()) {
            case "liberal", "duplicates", "escape", "fallback" ->
              opts.put(new XdmAtomicValue(e.getKey().getLocalName()), e.getValue());
            default -> {
            }
          }
        }
      }
    }
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    vars.put(new QName("s"), new XdmAtomicValue(text));
    vars.put(new QName("o"), new XdmMap(opts));
    try {
      return pc.xpath.evaluateForStep("parse-json($s, $o)", Map.of(), null, null, vars);
    } catch (XProcException e) {
      String code = e.getCode().getLocalName();
      if ("FOJS0003".equals(code)) {
        throw XProcException.xd(58, "Duplicate key in JSON object: " + e.getMessage(), e);
      }
      if ("FOJS0005".equals(code) || "XPTY0004".equals(code)) {
        throw XProcException.xd(59, "Invalid JSON parameters: " + e.getMessage(), e);
      }
      throw XProcException.xd(57, "Invalid JSON: " + e.getMessage(), e);
    }
  }

  public static boolean booleanParam(Map<QName, XdmValue> params, String name) {
    if (params == null) {
      return false;
    }
    XdmValue v = params.get(new QName(name));
    if (v == null || v.size() == 0) {
      return false;
    }
    String s = v.itemAt(0).getStringValue().trim();
    return "true".equals(s) || "1".equals(s) || "yes".equals(s);
  }
}
