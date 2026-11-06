package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.fop.apps.FOPException;
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.events.Event;
import org.apache.fop.events.EventFormatter;
import org.apache.fop.events.model.EventSeverity;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.XMLFilterImpl;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SAXDestination;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.runtime.Executor;
import nl.armatiek.xproc.internal.util.Xdm;

/**
 * The step of XProc 3.1 Paged Media: p:xsl-formatter renders an XSL-FO document with Apache
 * FOP, by default to PDF. The other output formats are those of FOP's renderers. Parameters
 * without a namespace set document metadata and rendering settings of the FOP user agent, or are
 * passed to the renderer as renderer options. Relative URIs in the src and background-image
 * properties are resolved against the base URI of the source document before FOP sees them.
 */
final class PagedMediaSteps {

  private static final String FO_NS = "http://www.w3.org/1999/XSL/Format";
  private static final String FOX_NS = "http://xmlgraphics.apache.org/fop/extensions";
  private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";
  private static final MediaType PDF = MediaType.parse(MimeConstants.MIME_PDF);

  /** FOP's output formats that open a window or print instead of producing a document. */
  private static final List<String> EXCLUDED = List.of(MimeConstants.MIME_FOP_AWT_PREVIEW,
      MimeConstants.MIME_FOP_PRINT);

  private PagedMediaSteps() {
  }

  static void register(StepRegistry r) {
    r.register("xsl-formatter", () -> PagedMediaSteps::xslFormatter);
  }

  private static void xslFormatter(StepContext ctx) {
    String ct = Xdm.string(ctx.option("content-type"));
    MediaType contentType = ct == null ? PDF : MediaType.parse(ct);
    FopFactory factory = ctx.pc().fop.factory();
    String format = outputFormat(factory, contentType);
    FOUserAgent agent = factory.newFOUserAgent();
    agent.getEventBroadcaster().addEventListener(event -> report(ctx, event));
    applyParameters(agent, Executor.toQNameMap(ctx.option("parameters"),
        ctx.optionNamespaces("parameters")));
    XdmNode source = ctx.input("source").getNode();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      Fop fop = factory.newFop(format, agent, out);
      ctx.processor().writeXdmValue(source, new SAXDestination(
          new BaseUriFilter(fop.getDefaultHandler(), source.getBaseURI())));
    } catch (FOPException | SaxonApiException e) {
      throw XProcException.xc(167, "The document cannot be formatted: " + message(e), e);
    } catch (XProcException e) {
      throw e;
    } catch (RuntimeException e) {
      throw XProcException.xc(167, "The document cannot be formatted: " + message(e), e);
    }
    Charset charset = contentType.getCharset(StandardCharsets.UTF_8);
    ctx.output("result", ctx.pc().loader.parse(out.toByteArray(), contentType, charset, null,
        Map.of(), Map.of(), false));
  }

  /** FOP's name of the output format of a content type; err:XC0204 if it has none. */
  private static String outputFormat(FopFactory factory, MediaType contentType) {
    String requested = contentType.toBaseString();
    for (String mime : factory.getRendererFactory().listSupportedMimeTypes()) {
      if (mime.equalsIgnoreCase(requested) && !EXCLUDED.contains(mime)) {
        return mime;
      }
    }
    throw XProcException.xc(204, "The content type " + requested
        + " is not supported by p:xsl-formatter");
  }

  private static void applyParameters(FOUserAgent agent, Map<QName, XdmValue> params) {
    for (Map.Entry<QName, XdmValue> e : params.entrySet()) {
      QName name = e.getKey();
      if (!name.getNamespaceUri().toString().isEmpty()) {
        continue;
      }
      String value = StreamSupport.stream(e.getValue().spliterator(), false)
          .map(XdmItem::getStringValue).collect(Collectors.joining(" "));
      String local = name.getLocalName();
      try {
        switch (local) {
          case "author" -> agent.setAuthor(value);
          case "title" -> agent.setTitle(value);
          case "subject" -> agent.setSubject(value);
          case "keywords" -> agent.setKeywords(value);
          case "creator" -> agent.setCreator(value);
          case "producer" -> agent.setProducer(value);
          case "creation-date" -> agent.setCreationDate(date(value));
          case "target-resolution" -> agent.setTargetResolution(Float.parseFloat(value));
          case "accessibility" -> agent.setAccessibility(Xdm.bool(e.getValue()));
          case "conserve-memory" -> agent.setConserveMemoryPolicy(Xdm.bool(e.getValue()));
          default -> rendererOptions(agent).put(local, value);
        }
      } catch (NumberFormatException | DateTimeParseException ex) {
        throw XProcException.xc(167, "Invalid value for the parameter " + local + ": " + value,
            ex);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rendererOptions(FOUserAgent agent) {
    return agent.getRendererOptions();
  }

  private static Date date(String value) {
    String v = value.trim();
    if (v.contains("T")) {
      try {
        return Date.from(OffsetDateTime.parse(v).toInstant());
      } catch (DateTimeParseException e) {
        return Date.from(LocalDateTime.parse(v).atZone(ZoneId.systemDefault()).toInstant());
      }
    }
    return Date.from(LocalDate.parse(v).atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * Passes FOP's warnings and errors (missing images, overflows, font substitutions) on as
   * messages. Its informational events, such as one for every rendered page, are dropped.
   */
  private static void report(StepContext ctx, Event event) {
    EventSeverity severity = event.getSeverity();
    if (severity == EventSeverity.INFO) {
      return;
    }
    MessageListener.Level level = severity == EventSeverity.WARN
        ? MessageListener.Level.WARNING : MessageListener.Level.ERROR;
    ctx.messages().message(level, ctx.stepName(), EventFormatter.format(event));
  }

  private static String message(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t
        && (t instanceof SaxonApiException || t instanceof SAXException)) {
      t = t.getCause();
    }
    return t.getMessage() == null ? t.toString() : t.getMessage();
  }

  /**
   * Makes relative URIs in src and background-image properties absolute, against the base URI
   * of their element (the document's base URI and xml:base attributes), as FOP itself resolves
   * them against the base URI of its configuration.
   */
  private static final class BaseUriFilter extends XMLFilterImpl {

    private final List<URI> bases = new ArrayList<>();

    BaseUriFilter(ContentHandler next, URI base) {
      setContentHandler(next);
      bases.add(base == null || base.toString().isEmpty() ? null : base);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts)
        throws SAXException {
      URI base = bases.getLast();
      String xmlBase = atts.getValue(XML_NS, "base");
      if (xmlBase != null) {
        base = resolve(base, xmlBase);
      }
      bases.add(base);
      Attributes result = atts;
      if (base != null && (FO_NS.equals(uri)
          || FOX_NS.equals(uri) && "external-document".equals(localName))) {
        AttributesImpl copy = null;
        for (int i = 0; i < atts.getLength(); i++) {
          String name = atts.getLocalName(i);
          if (atts.getURI(i).isEmpty() && (name.equals("src")
              || name.equals("background-image"))) {
            String absolute = absolute(atts.getValue(i), base);
            if (absolute != null) {
              copy = copy == null ? new AttributesImpl(atts) : copy;
              copy.setValue(i, absolute);
            }
          }
        }
        result = copy == null ? atts : copy;
      }
      super.startElement(uri, localName, qName, result);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      bases.removeLast();
      super.endElement(uri, localName, qName);
    }

    private static URI resolve(URI base, String ref) {
      try {
        URI u = new URI(ref.trim().replace(" ", "%20"));
        return base == null ? (u.isAbsolute() ? u : null) : base.resolve(u);
      } catch (URISyntaxException | IllegalArgumentException e) {
        return base;
      }
    }

    /** The value with an absolute URI, or null if it is absolute already or not a URI. */
    private static String absolute(String value, URI base) {
      String v = value.trim();
      boolean url = v.regionMatches(true, 0, "url(", 0, 4) && v.endsWith(")");
      String ref = url ? v.substring(4, v.length() - 1).trim() : v;
      if (ref.length() >= 2 && (ref.charAt(0) == '\'' || ref.charAt(0) == '"')
          && ref.charAt(ref.length() - 1) == ref.charAt(0)) {
        ref = ref.substring(1, ref.length() - 1);
      }
      if (ref.isEmpty() || !url && (ref.equals("none") || ref.equals("inherit"))) {
        return null;
      }
      try {
        URI u = new URI(ref.replace(" ", "%20"));
        if (u.isAbsolute()) {
          return null;
        }
        String resolved = base.resolve(u).toString();
        return url ? "url('" + resolved + "')" : resolved;
      } catch (URISyntaxException | IllegalArgumentException e) {
        return null;
      }
    }
  }
}
