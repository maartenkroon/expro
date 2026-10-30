package nl.armatiek.xproc.rest.service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import net.sf.saxon.s9api.ItemType;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.rest.model.Document;
import nl.armatiek.xproc.rest.model.Encoding;

/** Converts between the {@link Document} envelopes of the API and {@link XProcDocument}s. */
@Component
public class DocumentConverter {

  private static final QName ENCODING = new QName("encoding");

  private final XProcProcessor processor;

  public DocumentConverter(XProcProcessor processor) {
    this.processor = processor;
  }

  /** Creates a pipeline input document from a document envelope. */
  public XProcDocument toXProc(Document doc, Map<String, String> namespaces) {
    URI base = doc.getBaseUri();
    XProcDocument result;
    if (doc.getHref() != null) {
      if (doc.getContent() != null) {
        throw new BadRequestException("A document cannot have both content and an href");
      }
      URI href = base == null ? doc.getHref() : base.resolve(doc.getHref());
      result = processor.loadDocument(href, doc.getContentType());
    } else {
      MediaType ct = mediaType(doc.getContentType());
      result = parse(doc.getContent() == null ? "" : doc.getContent(), doc.getEncoding(), ct,
          base);
      if (base != null) {
        result = result.withProperty(processor.getSaxonProcessor(), Ns.BASE_URI,
            new XdmAtomicValue(base));
      }
    }
    if (doc.getProperties() != null) {
      for (Map.Entry<String, String> p : doc.getProperties().entrySet()) {
        QName name = Names.parse(p.getKey(), namespaces);
        if (name.equals(Ns.CONTENT_TYPE) || name.equals(Ns.BASE_URI)) {
          throw new BadRequestException("Use contentType and baseUri to set the " + name
              + " property");
        }
        result = result.withProperty(processor.getSaxonProcessor(), name, untyped(p.getValue()));
      }
    }
    return result;
  }

  /** Parses raw content (for example a request body) as a document. */
  public XProcDocument parse(byte[] bytes, MediaType ct, URI base) {
    return processor.parseDocument(bytes, ct, base);
  }

  private XProcDocument parse(String content, Encoding encoding, MediaType ct, URI base) {
    if (encoding == Encoding.BASE64) {
      byte[] bytes;
      try {
        bytes = Base64.getMimeDecoder().decode(content);
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Invalid base64 content: " + e.getMessage(), e);
      }
      return processor.parseDocument(bytes, ct, base);
    }
    // Text content is a Java string: parse its UTF-8 encoding, overriding a declared charset,
    // but keep the content type as given
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (ct.getCharset() == null || ct.getKind() == MediaType.Kind.OTHER) {
      return processor.parseDocument(bytes, ct, base);
    }
    XProcDocument d = processor.parseDocument(bytes, ct.withParameter("charset", "utf-8"), base);
    return d.withProperty(processor.getSaxonProcessor(), Ns.CONTENT_TYPE,
        new XdmAtomicValue(ct.toString()));
  }

  /** Creates a document envelope for a pipeline output document. */
  public Document toApi(XProcDocument doc, Map<QName, XdmValue> serialization) {
    Document result = new Document()
        .contentType(doc.getContentType().toString())
        .baseUri(doc.getBaseURI());
    if (doc.isBinary()) {
      result.content(Base64.getEncoder().encodeToString(doc.getBinary()))
          .encoding(Encoding.BASE64);
    } else {
      result.content(processor.serializeToString(doc, serialization)).encoding(Encoding.TEXT);
    }
    Map<String, String> props = new LinkedHashMap<>();
    for (Map.Entry<QName, XdmValue> p : doc.getProperties().entrySet()) {
      if (!p.getKey().equals(Ns.CONTENT_TYPE) && !p.getKey().equals(Ns.BASE_URI)) {
        props.put(Names.format(p.getKey()), stringValue(p.getValue()));
      }
    }
    result.properties(props.isEmpty() ? null : props);
    return result;
  }

  /** The serialized bytes of a document and the media type to send them with. */
  public record Serialized(byte[] bytes, String contentType) {
  }

  /** Serializes a document for a raw response body. */
  public Serialized serialize(XProcDocument doc, Map<QName, XdmValue> serialization) {
    MediaType ct = doc.getContentType();
    if (doc.isBinary()) {
      return new Serialized(doc.getBinary(), ct.toString());
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    processor.serialize(doc, out, serialization);
    if (ct.getCharset() == null) {
      XdmValue enc = doc.getSerializationProperties().get(ENCODING);
      if (enc == null && serialization != null) {
        enc = serialization.get(ENCODING);
      }
      ct = ct.withParameter("charset", enc == null ? "utf-8" : enc.itemAt(0).getStringValue().toLowerCase());
    }
    return new Serialized(out.toByteArray(), ct.toString());
  }

  /** Parses a media type given by a client. Defaults to application/xml. */
  public static MediaType mediaType(String value) {
    if (value == null || value.isBlank()) {
      return MediaType.XML;
    }
    MediaType ct = MediaType.tryParse(value);
    if (ct == null) {
      throw new BadRequestException("Invalid content type: " + value);
    }
    return ct;
  }

  static XdmValue untyped(String value) {
    try {
      return new XdmAtomicValue(value, ItemType.UNTYPED_ATOMIC);
    } catch (SaxonApiException e) {
      throw new XProcException(new QName(Ns.XPROC_ERROR, "XD0001"), e.getMessage(), e);
    }
  }

  private static String stringValue(XdmValue value) {
    StringJoiner sj = new StringJoiner(" ");
    for (XdmItem item : value) {
      if (item.isAtomicValue() || item instanceof XdmNode) {
        sj.add(item.getStringValue());
      } else {
        sj.add(item.toString());
      }
    }
    return sj.toString();
  }
}
