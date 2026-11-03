package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.internal.ProcessorContext;
import nl.armatiek.xproc.internal.util.TreeWriter;

/**
 * Builds validation reports in the Extensible Validation Report Language (XVRL).
 */
final class Xvrl {

  static final String NS = "http://www.xproc.org/ns/xvrl";

  /** One finding of a validator. */
  record Detection(String severity, String message, String href, int line, int column,
      String xpath, String code) {

    static Detection error(String message, String href, int line, int column) {
      return new Detection("error", message, href, line, column, null, null);
    }
  }

  private final String validator;
  private final String validatorVersion;
  private URI document;
  private URI schema;
  private String schemaType;
  private final List<Detection> detections = new ArrayList<>();

  Xvrl(String validator, String validatorVersion) {
    this.validator = validator;
    this.validatorVersion = validatorVersion;
  }

  Xvrl document(URI uri) {
    this.document = uri;
    return this;
  }

  Xvrl schema(URI uri, String schemaTypeNamespace) {
    this.schema = uri;
    this.schemaType = schemaTypeNamespace;
    return this;
  }

  void add(Detection d) {
    detections.add(d);
  }

  List<Detection> detections() {
    return detections;
  }

  /** True if there are no detections of severity error or fatal-error. */
  boolean isValid() {
    return detections.stream().noneMatch(d -> d.severity().equals("error")
        || d.severity().equals("fatal-error"));
  }

  XdmNode toDocument(Processor processor) {
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(null);
    tw.addStartElement(q("report"));
    tw.addStartElement(q("metadata"));
    tw.addStartElement(q("timestamp"));
    tw.addText(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).toString());
    tw.addEndElement();
    if (document != null) {
      tw.addStartElement(q("document"));
      tw.addAttribute(new QName("href"), document.toString());
      tw.addEndElement();
    }
    if (schema != null || schemaType != null) {
      tw.addStartElement(q("schema"));
      if (schema != null) {
        tw.addAttribute(new QName("href"), schema.toString());
      }
      if (schemaType != null) {
        tw.addAttribute(new QName("schematypens"), schemaType);
      }
      tw.addEndElement();
    }
    tw.addStartElement(q("validator"));
    tw.addAttribute(new QName("name"), validator);
    if (validatorVersion != null) {
      tw.addAttribute(new QName("version"), validatorVersion);
    }
    tw.addEndElement();
    tw.addStartElement(q("creator"));
    tw.addAttribute(new QName("name"), ProcessorContext.PRODUCT_NAME);
    tw.addAttribute(new QName("version"), ProcessorContext.PRODUCT_VERSION);
    tw.addEndElement();
    tw.addEndElement();

    int[] counts = new int[4];
    for (Detection d : detections) {
      switch (d.severity()) {
        case "fatal-error" -> counts[0]++;
        case "error" -> counts[1]++;
        case "warning" -> counts[2]++;
        default -> counts[3]++;
      }
      tw.addStartElement(q("detection"));
      tw.addAttribute(new QName("severity"), d.severity());
      if (d.code() != null) {
        tw.addAttribute(new QName("code"), d.code());
      }
      if (d.href() != null || d.line() > 0 || d.xpath() != null) {
        tw.addStartElement(q("location"));
        if (d.href() != null) {
          tw.addAttribute(new QName("href"), d.href());
        }
        if (d.line() > 0) {
          tw.addAttribute(new QName("line"), Integer.toString(d.line()));
        }
        if (d.column() > 0) {
          tw.addAttribute(new QName("column"), Integer.toString(d.column()));
        }
        if (d.xpath() != null) {
          tw.addAttribute(new QName("xpath"), d.xpath());
        }
        tw.addEndElement();
      }
      tw.addStartElement(q("message"));
      tw.addText(d.message() == null ? "" : d.message());
      tw.addEndElement();
      tw.addEndElement();
    }
    tw.addStartElement(q("digest"));
    tw.addAttribute(new QName("valid"), Boolean.toString(isValid()));
    tw.addAttribute(new QName("fatal-error-count"), Integer.toString(counts[0]));
    tw.addAttribute(new QName("error-count"), Integer.toString(counts[1]));
    tw.addAttribute(new QName("warning-count"), Integer.toString(counts[2]));
    tw.addAttribute(new QName("info-count"), Integer.toString(counts[3]));
    tw.addEndElement();
    tw.addEndElement();
    return tw.endDocument();
  }

  private static QName q(String local) {
    return new QName("xvrl", NS, local);
  }
}
