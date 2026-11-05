package nl.armatiek.xproc.openapi.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.XProcResult;
import nl.armatiek.xproc.openapi.OpenApiNs;

/**
 * Writes the primary output of a pipeline as the HTTP response. The document is serialized with
 * the serialization parameters of the primary output port and of the document itself; the
 * Content-Type follows from the media-type or method parameter, or else from the content type
 * of the document. No document gives 204 No Content. The document properties
 * {@code resp:status} (an integer) and {@code resp:headers} (a map of header names to values),
 * in the {@link OpenApiNs#RESPONSE} namespace, set the status code and headers.
 */
@Component
public class ResponseWriter {

  private static final QName METHOD = new QName("method");
  private static final QName MEDIA_TYPE = new QName("media-type");
  private static final QName ENCODING = new QName("encoding");
  private static final QName STATUS = new QName(OpenApiNs.RESPONSE, "status");
  private static final QName HEADERS = new QName(OpenApiNs.RESPONSE, "headers");

  private final XProcProcessor processor;

  public ResponseWriter(XProcProcessor processor) {
    this.processor = processor;
  }

  public void write(XProcPipeline pipeline, XProcResult result, HttpServletResponse response)
      throws IOException {
    String port = result.getPrimaryPort();
    List<XProcDocument> docs = port == null ? List.of() : result.getPrimaryDocuments();
    if (docs.isEmpty()) {
      response.setStatus(HttpStatus.NO_CONTENT.value());
      return;
    }
    if (docs.size() > 1) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The primary output port '"
          + port + "' produced " + docs.size() + " documents; a response holds one");
    }
    XProcDocument doc = docs.get(0);
    Map<QName, XdmValue> portParams = pipeline.getSerialization(port);
    Map<QName, XdmValue> params = new LinkedHashMap<>(portParams);
    params.putAll(doc.getSerializationProperties());

    byte[] body;
    if (doc.isBinary()) {
      body = doc.getBinary();
    } else {
      // Serialized before anything is sent, so a serialization error still gives a problem
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      processor.serialize(doc, out, portParams);
      body = out.toByteArray();
    }

    int status = HttpStatus.OK.value();
    XdmValue s = doc.getProperty(STATUS);
    if (s != null && s.size() == 1) {
      try {
        status = Integer.parseInt(s.itemAt(0).getStringValue().trim());
      } catch (NumberFormatException e) {
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid response status: "
            + s.itemAt(0).getStringValue());
      }
      if (status < 100 || status > 599) {
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Invalid response status: "
            + status);
      }
    }
    response.setStatus(status);
    XdmValue h = doc.getProperty(HEADERS);
    if (h != null && h.size() == 1 && h.itemAt(0) instanceof XdmMap map) {
      for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
        for (XdmItem v : e.getValue()) {
          response.addHeader(e.getKey().getStringValue(), v.getStringValue());
        }
      }
    }
    response.setContentType(contentType(doc, params));
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  /** The media type of the serialized document. */
  static String contentType(XProcDocument doc, Map<QName, XdmValue> params) {
    MediaType ct = doc.getContentType();
    if (doc.isBinary()) {
      return ct.toString();
    }
    String mediaType = string(params.get(MEDIA_TYPE));
    String method = string(params.get(METHOD));
    MediaType.Kind kind = doc.getKind();
    MediaType result;
    if (mediaType != null) {
      result = MediaType.tryParse(mediaType);
    } else if (method == null) {
      result = ct;
    } else {
      result = switch (method) {
        case "xml" -> kind == MediaType.Kind.XML ? ct : MediaType.XML;
        case "xhtml" -> MediaType.tryParse("application/xhtml+xml");
        case "html" -> kind == MediaType.Kind.HTML ? ct : MediaType.HTML;
        case "text" -> kind == MediaType.Kind.TEXT ? ct : MediaType.tryParse("text/plain");
        case "json" -> kind == MediaType.Kind.JSON ? ct : MediaType.tryParse("application/json");
        default -> ct;
      };
    }
    if (result == null) {
      result = ct;
    }
    if (result.getCharset() == null) {
      String enc = string(params.get(ENCODING));
      result = result.withParameter("charset", enc == null ? "utf-8" : enc.toLowerCase());
    }
    return result.toString();
  }

  private static String string(XdmValue v) {
    return v == null || v.size() == 0 ? null : v.itemAt(0).getStringValue();
  }
}
