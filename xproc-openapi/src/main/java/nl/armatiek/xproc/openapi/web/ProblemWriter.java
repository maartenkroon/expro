package nl.armatiek.xproc.openapi.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.PortSignature;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.openapi.api.OperationSpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes errors as RFC 9457 problem details, in JSON ({@code application/problem+json}) or XML
 * ({@code application/problem+xml}). See {@link #format} for how the format is chosen.
 */
@Component
public class ProblemWriter {

  /** The namespace of problem details in XML (RFC 9457, appendix B). */
  public static final String NS = "urn:ietf:rfc:7807";

  private static final QName METHOD = new QName("method");

  /** The representations of a problem. */
  public enum Format {
    JSON, XML
  }

  private final JsonMapper json;

  public ProblemWriter(JsonMapper json) {
    this.json = json;
  }

  /**
   * Chooses the format of an error response. The format follows the serialization method of the
   * primary output port of the pipeline (json gives JSON; xml, xhtml and html give XML). Without
   * a method, it follows the content type a result would have had, as the XProc serialization
   * rules do: the content types declared on the primary output port, then the content types of
   * the responses of the operation. Otherwise the Accept header decides, and JSON is the
   * default.
   *
   * @param pipeline the pipeline of the request, or null if it is not known (yet)
   * @param operation the operation of the request, or null if it is not known
   */
  public Format format(XProcPipeline pipeline, OperationSpec operation,
      HttpServletRequest request) {
    if (pipeline != null && pipeline.getPrimaryOutput() != null) {
      String port = pipeline.getPrimaryOutput();
      XdmValue method = pipeline.getSerialization(port).get(METHOD);
      if (method != null && method.size() > 0) {
        switch (method.itemAt(0).getStringValue()) {
          case "json" -> {
            return Format.JSON;
          }
          case "xml", "xhtml", "html" -> {
            return Format.XML;
          }
          default -> {
            // text and other methods: decided by content type below
          }
        }
      }
      for (PortSignature p : pipeline.getOutputs()) {
        if (p.name().equals(port) && p.contentTypes() != null) {
          for (String ct : p.contentTypes().trim().split("\\s+")) {
            Format f = ct.startsWith("-") ? null : forContentType(ct);
            if (f != null) {
              return f;
            }
          }
        }
      }
    }
    if (operation != null) {
      for (String ct : operation.responseContentTypes()) {
        Format f = forContentType(ct);
        if (f != null) {
          return f;
        }
      }
    }
    String accept = request.getHeader("Accept");
    if (accept != null) {
      String a = accept.toLowerCase(Locale.ROOT);
      if (a.contains("xml") && !a.contains("json")) {
        return Format.XML;
      }
    }
    return Format.JSON;
  }

  /** The format for a content type (or XProc content type shortcut), or null if neither. */
  static Format forContentType(String contentType) {
    String ct = contentType.toLowerCase(Locale.ROOT);
    int semi = ct.indexOf(';');
    if (semi >= 0) {
      ct = ct.substring(0, semi);
    }
    ct = ct.trim();
    if (ct.equals("json") || ct.equals("application/json") || ct.endsWith("+json")) {
      return Format.JSON;
    }
    if (ct.equals("xml") || ct.equals("html") || ct.endsWith("/xml") || ct.endsWith("+xml")
        || ct.equals("text/html")) {
      return Format.XML;
    }
    return null;
  }

  /** Writes a problem. */
  public void write(HttpServletResponse response, Format format, HttpStatus status,
      String detail, String instance, Map<String, String> headers, Throwable cause)
      throws IOException {
    Map<String, Object> members = new LinkedHashMap<>();
    members.put("type", "about:blank");
    members.put("title", status.getReasonPhrase());
    members.put("status", status.value());
    if (detail != null) {
      members.put("detail", detail);
    }
    members.put("instance", instance);
    List<XdmNode> details = List.of();
    XProcException xe = xprocException(cause);
    if (xe != null) {
      members.put("code", eqName(xe.getCode()));
      if (xe.getCauseCode() != null) {
        members.put("causeCode", eqName(xe.getCauseCode()));
      }
      if (xe.getStepName() != null) {
        members.put("step", xe.getStepName());
      }
      if (xe.getStepType() != null) {
        members.put("stepType", eqName(xe.getStepType()));
      }
      if (xe.getLocation() != null) {
        members.put("location", xe.getLocation());
      }
      if (xe.getLine() > 0) {
        members.put("line", xe.getLine());
      }
      if (xe.getColumn() > 0) {
        members.put("column", xe.getColumn());
      }
      details = xe.getDetails();
    }
    response.reset();
    response.setStatus(status.value());
    headers.forEach(response::setHeader);
    byte[] body;
    if (format == Format.XML) {
      response.setContentType("application/problem+xml; charset=utf-8");
      body = xml(members, details).getBytes(StandardCharsets.UTF_8);
    } else {
      if (!details.isEmpty()) {
        members.put("details", details.stream().map(this::serialize).toList());
      }
      response.setContentType("application/problem+json");
      body = json.writeValueAsBytes(members);
    }
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  private String xml(Map<String, Object> members, List<XdmNode> details) {
    StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<problem xmlns=\"").append(NS).append("\">");
    for (Map.Entry<String, Object> m : members.entrySet()) {
      sb.append('<').append(m.getKey()).append('>').append(escape(String.valueOf(m.getValue())))
          .append("</").append(m.getKey()).append('>');
    }
    if (!details.isEmpty()) {
      sb.append("<details>");
      details.forEach(d -> sb.append(serialize(d)));
      sb.append("</details>");
    }
    return sb.append("</problem>").toString();
  }

  private String serialize(XdmNode node) {
    // XdmNode.toString() serializes the node without an XML declaration
    return node.toString();
  }

  private static XProcException xprocException(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof XProcException x) {
        return x;
      }
    }
    return null;
  }

  private static String eqName(QName name) {
    return name.getEQName();
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '&' -> sb.append("&amp;");
        default -> {
          if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
