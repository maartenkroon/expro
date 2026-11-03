package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.util.TreeWriter;

/**
 * p:send-mail of the XProc 3.1 Mail Steps specification. The first source document is a
 * message in the XML format of draft-klyne-message-rfc822-xml; other documents are attachments.
 * Mail is sent over SMTP with Jakarta Mail.
 */
final class MailSteps {

  static final String EMX = "URN:ietf:params:email-xml:";
  static final String RFC822 = "URN:ietf:params:rfc822:";

  private MailSteps() {
  }

  static void register(StepRegistry r) {
    r.register("send-mail", () -> MailSteps::sendMail);
  }

  private static void sendMail(StepContext ctx) {
    List<XProcDocument> docs = ctx.inputs("source");
    XdmNode message = docs.isEmpty() ? null : root(docs.get(0).getNode());
    if (message == null || !EMX.equals(message.getNodeName().getNamespace())
        || !"Message".equals(message.getNodeName().getLocalName())) {
      throw XProcException.xc(161, "The first document on the source port is not an "
          + "emx:Message");
    }
    Map<String, XdmValue> params = map(ctx, "parameters");
    Map<String, XdmValue> auth = map(ctx, "auth");
    String host = string(params, "host", 160, "localhost");
    int port = integer(params, "port", 160, 25);
    boolean sendAuth = bool(params, "send-authorization", 160, true);
    boolean starttls = bool(params, "starttls", 160, false);
    boolean ssl = bool(params, "ssl", 160, false);
    boolean debug = bool(params, "debug", 160, false);
    String username = string(auth, "username", 159, null);
    String password = string(auth, "password", 159, null);
    try {
      ctx.pc().checkAccess(new URI("smtp", null, host, port, null, null, null));
    } catch (java.net.URISyntaxException e) {
      throw XProcException.xc(160, "Invalid SMTP host: " + host);
    }

    Properties props = new Properties();
    String protocol = ssl ? "smtps" : "smtp";
    props.put("mail.transport.protocol", protocol);
    props.put("mail." + protocol + ".host", host);
    props.put("mail." + protocol + ".port", Integer.toString(port));
    props.put("mail." + protocol + ".starttls.enable", Boolean.toString(starttls));
    boolean authenticate = sendAuth && username != null;
    props.put("mail." + protocol + ".auth", Boolean.toString(authenticate));
    Session session = Session.getInstance(props);
    session.setDebug(debug);

    Map<QName, XdmValue> serialization = DocumentSerializer.toParams(ctx.option("serialization"));
    try {
      MimeMessage mime = new MimeMessage(session);
      headers(mime, message);
      List<MimeBodyPart> parts = new ArrayList<>();
      XdmNode content = child(message, EMX, "content");
      int next = 1;
      if (content != null) {
        parts.add(contentPart(ctx, content));
      } else if (docs.size() > 1) {
        parts.add(documentPart(ctx, docs.get(1), serialization, false));
        next = 2;
      }
      for (int i = next; i < docs.size(); i++) {
        parts.add(documentPart(ctx, docs.get(i), serialization, true));
      }
      if (parts.size() == 1) {
        MimeBodyPart only = parts.get(0);
        mime.setDataHandler(only.getDataHandler());
        if (only.getFileName() != null) {
          mime.setFileName(only.getFileName());
        }
      } else if (parts.size() > 1) {
        MimeMultipart mp = new MimeMultipart("mixed");
        for (MimeBodyPart p : parts) {
          mp.addBodyPart(p);
        }
        mime.setContent(mp);
      } else {
        mime.setText("", "UTF-8");
      }
      mime.saveChanges();
      if (authenticate) {
        Transport.send(mime, username, password == null ? "" : password);
      } else {
        Transport.send(mime);
      }
    } catch (MessagingException e) {
      throw XProcException.xc(162, "The mail cannot be sent: " + e.getMessage());
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), StepSupport.cResult(
        ctx.processor(), "true", null), MediaType.XML, (URI) null));
  }

  /** Sets the RFC 822 headers given by the rfc822: children of the message. */
  private static void headers(MimeMessage mime, XdmNode message) throws MessagingException {
    for (XdmNode h : message.children()) {
      if (h.getNodeKind() != XdmNodeKind.ELEMENT || !RFC822.equals(h.getNodeName()
          .getNamespace())) {
        continue;
      }
      String name = h.getNodeName().getLocalName();
      switch (name) {
        case "from" -> {
          Address[] a = addresses(h);
          if (a.length > 0) {
            mime.addFrom(a);
          }
        }
        case "to" -> mime.addRecipients(Message.RecipientType.TO, addresses(h));
        case "cc" -> mime.addRecipients(Message.RecipientType.CC, addresses(h));
        case "bcc" -> mime.addRecipients(Message.RecipientType.BCC, addresses(h));
        case "reply-to" -> mime.setReplyTo(addresses(h));
        case "sender" -> {
          Address[] a = addresses(h);
          if (a.length > 0) {
            mime.setSender(a[0]);
          }
        }
        case "subject" -> mime.setSubject(h.getStringValue().strip(), "UTF-8");
        default -> mime.addHeader(headerName(name), h.getStringValue().strip());
      }
    }
  }

  private static String headerName(String local) {
    StringBuilder sb = new StringBuilder();
    boolean upper = true;
    for (char c : local.toCharArray()) {
      sb.append(upper ? Character.toUpperCase(c) : c);
      upper = c == '-';
    }
    return sb.toString();
  }

  /** The addresses of a header: emx:Address elements, or a comma separated list as text. */
  private static Address[] addresses(XdmNode header) {
    List<Address> list = new ArrayList<>();
    try {
      boolean structured = false;
      for (XdmNode a : header.children()) {
        if (a.getNodeKind() == XdmNodeKind.ELEMENT && EMX.equals(a.getNodeName().getNamespace())
            && "Address".equals(a.getNodeName().getLocalName())) {
          structured = true;
          XdmNode adrs = child(a, EMX, "adrs");
          XdmNode name = child(a, EMX, "name");
          String email = adrs == null ? "" : adrs.getStringValue().strip();
          if (email.regionMatches(true, 0, "mailto:", 0, 7)) {
            email = email.substring(7);
          }
          list.add(new InternetAddress(email, name == null ? null
              : name.getStringValue().strip(), "UTF-8"));
        }
      }
      if (!structured && !header.getStringValue().isBlank()) {
        for (InternetAddress a : InternetAddress.parse(header.getStringValue().strip())) {
          list.add(a);
        }
      }
    } catch (MessagingException | java.io.UnsupportedEncodingException e) {
      throw XProcException.xc(161, "Invalid address in " + header.getNodeName().getLocalName()
          + ": " + e.getMessage());
    }
    return list.toArray(new Address[0]);
  }

  /** The body given by emx:content: HTML if it contains elements, otherwise text. */
  private static MimeBodyPart contentPart(StepContext ctx, XdmNode content)
      throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    boolean markup = false;
    for (XdmNode c : content.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        markup = true;
      }
    }
    if (markup) {
      TreeWriter tw = new TreeWriter(ctx.processor());
      tw.startDocument(null);
      for (XdmNode c : content.children()) {
        tw.addSubtree(c);
      }
      XProcDocument html = XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.HTML,
          (URI) null);
      part.setContent(ctx.pc().serializer.serializeToString(html, Map.of(new QName("method"),
          new XdmAtomicValue(new QName("html")))), "text/html; charset=UTF-8");
    } else {
      String type = content.getAttributeValue(new QName("type"));
      MediaType ct = MediaType.tryParse(type == null ? "text/plain" : type);
      if (ct == null) {
        throw XProcException.xc(161, "Invalid content type: " + type);
      }
      part.setContent(content.getStringValue().strip(), ct.withoutParameters()
          + "; charset=UTF-8");
    }
    return part;
  }

  /** A body part for a source document, serialized as p:store would. */
  private static MimeBodyPart documentPart(StepContext ctx, XProcDocument doc,
      Map<QName, XdmValue> serialization, boolean attachment) throws MessagingException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ctx.pc().serializer.serialize(doc, out, serialization);
    MediaType ct = doc.getContentType();
    if (!doc.isBinary() && ct.getCharset() == null) {
      ct = ct.withParameter("charset", "UTF-8");
    }
    MimeBodyPart part = new MimeBodyPart();
    part.setDataHandler(new DataHandler(new ByteArrayDataSource(out.toByteArray(),
        ct.toString())));
    if (attachment) {
      part.setDisposition(MimeBodyPart.ATTACHMENT);
      String name = fileName(doc);
      if (name != null) {
        part.setFileName(name);
      }
    }
    return part;
  }

  private static String fileName(XProcDocument doc) {
    URI base = doc.getBaseURI();
    if (base == null || base.getPath() == null) {
      return null;
    }
    String path = base.getPath();
    int slash = path.lastIndexOf('/');
    String name = slash < 0 ? path : path.substring(slash + 1);
    return name.isEmpty() ? null : name;
  }

  // =========================================================================================
  // Options

  /** The entries of a map option by the local name of their key. */
  private static Map<String, XdmValue> map(StepContext ctx, String option) {
    Map<String, XdmValue> m = new LinkedHashMap<>();
    XdmValue v = ctx.option(option);
    if (v.size() == 1 && v.itemAt(0) instanceof XdmMap map) {
      for (var e : map.entrySet()) {
        XdmAtomicValue key = e.getKey();
        String name;
        try {
          name = key.getQNameValue() != null ? key.getQNameValue().getLocalName()
              : key.getStringValue();
        } catch (RuntimeException ex) {
          name = key.getStringValue();
        }
        m.put(name, e.getValue());
      }
    }
    return m;
  }

  private static XdmAtomicValue single(Map<String, XdmValue> m, String key, int error) {
    XdmValue v = m.get(key);
    if (v == null) {
      return null;
    }
    if (v.size() != 1 || !(v.itemAt(0) instanceof XdmAtomicValue a)) {
      throw XProcException.xc(error, "The value of '" + key + "' must be a single atomic value");
    }
    return a;
  }

  private static String string(Map<String, XdmValue> m, String key, int error, String dflt) {
    XdmAtomicValue a = single(m, key, error);
    return a == null ? dflt : a.getStringValue();
  }

  private static int integer(Map<String, XdmValue> m, String key, int error, int dflt) {
    XdmAtomicValue a = single(m, key, error);
    if (a == null) {
      return dflt;
    }
    try {
      int i = Integer.parseInt(a.getStringValue().strip());
      if (i < 0 || i > 65535) {
        throw new NumberFormatException();
      }
      return i;
    } catch (NumberFormatException e) {
      throw XProcException.xc(error, "The value of '" + key + "' must be a port number");
    }
  }

  private static boolean bool(Map<String, XdmValue> m, String key, int error, boolean dflt) {
    XdmAtomicValue a = single(m, key, error);
    if (a == null) {
      return dflt;
    }
    String s = a.getStringValue().strip();
    return switch (s) {
      case "true", "1" -> true;
      case "false", "0" -> false;
      default -> throw XProcException.xc(error, "The value of '" + key + "' must be a boolean");
    };
  }

  private static XdmNode root(XdmNode doc) {
    if (doc == null) {
      return null;
    }
    if (doc.getNodeKind() == XdmNodeKind.ELEMENT) {
      return doc;
    }
    for (XdmNode c : doc.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        return c;
      }
    }
    return null;
  }

  private static XdmNode child(XdmNode parent, String ns, String local) {
    for (XdmNode c : parent.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT && ns.equals(c.getNodeName().getNamespace())
          && local.equals(c.getNodeName().getLocalName())) {
        return c;
      }
    }
    return null;
  }
}
