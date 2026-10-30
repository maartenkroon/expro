package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmArray;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmEmptySequence;
import net.sf.saxon.s9api.XdmFunctionItem;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentLoader;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.util.QNames;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;

/** General purpose steps. */
final class MiscSteps {

  private MiscSteps() {
  }

  static void register(StepRegistry r) {
    r.register("identity", () -> ctx -> {
      for (XProcDocument d : ctx.inputs("source")) {
        ctx.output("result", d);
      }
    });
    r.register("sink", () -> ctx -> {
    });
    r.register("count", () -> MiscSteps::count);
    r.register("error", () -> MiscSteps::error);
    r.register("message", () -> MiscSteps::message);
    r.register("sleep", () -> MiscSteps::sleep);
    r.register("set-properties", () -> MiscSteps::setProperties);
    r.register("cast-content-type", () -> MiscSteps::castContentType);
    r.register("compare", () -> MiscSteps::compare);
    r.register("encode", () -> MiscSteps::encode);
    r.register("json-join", () -> MiscSteps::jsonJoin);
    r.register("json-merge", () -> MiscSteps::jsonMerge);
    r.register("load", () -> MiscSteps::load);
    r.register("store", () -> MiscSteps::store);
    r.register("www-form-urldecode", () -> MiscSteps::urldecode);
    r.register("www-form-urlencode", () -> MiscSteps::urlencode);
    r.register("split-sequence", () -> MiscSteps::splitSequence);
  }

  static void count(StepContext ctx) {
    long limit = Xdm.integer(ctx.option("limit"), 0);
    long n = ctx.inputs("source").size();
    if (limit > 0 && n > limit) {
      n = limit;
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), StepSupport.cResult(ctx.processor(),
        Long.toString(n), null), MediaType.XML, (URI) null));
  }

  static void error(StepContext ctx) {
    QName code = ((XdmAtomicValue) ctx.option("code").itemAt(0)).getQNameValue();
    StringBuilder msg = new StringBuilder();
    List<XdmNode> details = new ArrayList<>();
    for (XProcDocument d : ctx.inputs("source")) {
      msg.append(d.getNode().getStringValue());
      for (XdmNode c : d.getNode().children()) {
        details.add(c);
      }
    }
    XProcException e = new XProcException(code, msg.isEmpty() ? "p:error raised "
        + QNames.display(code) : msg.toString().trim());
    for (XdmNode n : details) {
      e.addDetail(n);
    }
    throw e;
  }

  static void message(StepContext ctx) {
    if (Xdm.bool(ctx.option("test"))) {
      XdmValue v = ctx.option("select");
      String text = ctx.pc().serializer.serializeAsText(v);
      ctx.messages().message(MessageListener.Level.INFO, ctx.stepName(), text);
    }
    for (XProcDocument d : ctx.inputs("source")) {
      ctx.output("result", d);
    }
  }

  static void sleep(StepContext ctx) {
    String duration = Xdm.string(ctx.option("duration")).trim();
    long millis;
    try {
      double secs = Double.parseDouble(duration);
      if (secs < 0 || Double.isNaN(secs)) {
        throw XProcException.xd(36, "Invalid duration: " + duration);
      }
      millis = (long) (secs * 1000);
    } catch (NumberFormatException e) {
      try {
        XdmValue v = ctx.pc().xpath.evaluateForStep(
            "xs:dayTimeDuration($d) div xs:dayTimeDuration('PT0.001S')", Map.of("xs", Ns.XS),
            null, null, Map.of(new QName("d"), new XdmAtomicValue(duration)));
        millis = (long) Double.parseDouble(v.itemAt(0).getStringValue());
        if (millis < 0) {
          throw XProcException.xd(36, "Invalid duration: " + duration);
        }
      } catch (XProcException ex) {
        throw XProcException.xd(36, "Invalid duration: " + duration);
      }
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    for (XProcDocument d : ctx.inputs("source")) {
      ctx.output("result", d);
    }
  }

  static void setProperties(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    boolean merge = Xdm.bool(ctx.option("merge"));
    XdmMap map = (XdmMap) ctx.option("properties").itemAt(0);
    Map<QName, XdmValue> props = new LinkedHashMap<>();
    if (merge) {
      props.putAll(src.getProperties());
    } else {
      props.put(Ns.CONTENT_TYPE, src.getProperty(Ns.CONTENT_TYPE));
    }
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      QName k = e.getKey().getQNameValue();
      if (k.equals(Ns.CONTENT_TYPE)) {
        throw XProcException.xc(69, "The content-type property cannot be set");
      }
      if (k.equals(Ns.BASE_URI)) {
        String b = Xdm.string(e.getValue());
        try {
          if (b != null && !new URI(b).isAbsolute()) {
            throw XProcException.xd(64, "The base URI is not absolute: " + b);
          }
        } catch (java.net.URISyntaxException ex) {
          throw XProcException.xd(64, "Invalid base URI: " + b);
        }
      }
      if (k.equals(Ns.SERIALIZATION)) {
        XdmValue sv = e.getValue();
        if (sv.size() != 1 || !(sv.itemAt(0) instanceof XdmMap)) {
          throw XProcException.xd(70, "The serialization property must be a map");
        }
        try {
          sv = ctx.pc().types.convert(sv,
              "map(Q{http://www.w3.org/2001/XMLSchema}QName, item()*)",
              ctx.optionNamespaces("properties"), null);
        } catch (XProcException ex) {
          throw XProcException.xd(70, "Invalid serialization property: " + ex.getMessage());
        }
        props.put(k, sv);
        continue;
      }
      props.put(k, e.getValue());
    }
    if (!merge && !props.containsKey(Ns.BASE_URI)) {
      props.put(Ns.BASE_URI, XdmEmptySequence.getInstance());
    }
    ctx.output("result", src.withProperties(ctx.processor(), props));
  }

  // ---------------------------------------------------------------------------------------
  // p:cast-content-type

  static void castContentType(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    MediaType target = MediaType.parse(Xdm.string(ctx.option("content-type")));
    Map<QName, XdmValue> params = nl.armatiek.xproc.internal.runtime.Executor.toQNameMap(
        ctx.option("parameters"), ctx.optionNamespaces("parameters"));
    MediaType from = src.getContentType();
    var p = ctx.processor();
    if (src.getKind() == MediaType.Kind.XML) {
      XdmNode dataRoot = rootElement(src.getNode());
      if (dataRoot != null && dataRoot.getNodeName().equals(Ns.c("data"))) {
        Map<QName, XdmValue> props = new LinkedHashMap<>(src.getProperties());
        props.remove(Ns.CONTENT_TYPE);
        props.remove(Ns.SERIALIZATION);
        ctx.output("result", decodeData(ctx, src, dataRoot, target, props));
        return;
      }
    }
    if (from.toBaseString().equals(target.toBaseString())) {
      ctx.output("result", src.withProperty(p, Ns.CONTENT_TYPE,
          new XdmAtomicValue(target.toString())));
      return;
    }
    MediaType.Kind fk = src.getKind();
    MediaType.Kind tk = target.getKind();
    Map<QName, XdmValue> props = new LinkedHashMap<>(src.getProperties());
    props.remove(Ns.CONTENT_TYPE);
    Map<QName, XdmValue> noSer = new LinkedHashMap<>(props);
    noSer.remove(Ns.SERIALIZATION);
    switch (fk) {
      case XML -> {
        XdmNode root = rootElement(src.getNode());
        if (root != null && root.getNodeName().equals(Ns.c("data"))) {
          ctx.output("result", decodeData(ctx, src, root, target, noSer));
          return;
        }
        switch (tk) {
          case XML -> ctx.output("result", XProcDocument.of(p, src.getNode(), target, props));
          case HTML -> ctx.output("result", XProcDocument.of(p, src.getNode(), target, noSer));
          case JSON -> ctx.output("result", XProcDocument.of(p, xmlToJson(ctx, src), target,
              noSer));
          case TEXT -> ctx.output("result", serializeToText(ctx, src, target, noSer));
          default -> ctx.output("result", XProcDocument.ofBinary(p,
              ctx.pc().serializer.serializeToBytes(src, null), target, noSer));
        }
      }
      case HTML -> {
        switch (tk) {
          case XML -> ctx.output("result", XProcDocument.of(p, src.getNode(), target, noSer));
          case HTML -> ctx.output("result", XProcDocument.of(p, src.getNode(), target, props));
          case TEXT -> ctx.output("result", serializeToText(ctx, src, target, noSer));
          case JSON -> throw XProcException.xc(71, "Cannot cast HTML to JSON");
          default -> ctx.output("result", XProcDocument.ofBinary(p,
              ctx.pc().serializer.serializeToBytes(src, null), target, noSer));
        }
      }
      case JSON -> {
        switch (tk) {
          case XML -> {
            String json = jsonText(ctx, src.getValue());
            XdmValue x = ctx.pc().xpath.evaluateForStep("json-to-xml($s)", Map.of(), null,
                null, Map.of(new QName("s"), new XdmAtomicValue(json)));
            ctx.output("result", XProcDocument.of(p, x, target, noSer));
          }
          case JSON -> ctx.output("result", XProcDocument.of(p, src.getValue(), target, props));
          case TEXT -> ctx.output("result", serializeToText(ctx, src, target, noSer));
          case HTML -> throw XProcException.xc(71, "Cannot cast JSON to HTML");
          default -> ctx.output("result", XProcDocument.ofBinary(p,
              ctx.pc().serializer.serializeToBytes(src, null), target, noSer));
        }
      }
      case TEXT -> {
        String text = StepSupport.text(src);
        URI base = src.getBaseURI();
        switch (tk) {
          case XML -> ctx.output("result", XProcDocument.of(p, ctx.pc().loader.parseXml(text,
              base), target, noSer));
          case HTML -> ctx.output("result", XProcDocument.of(p, ctx.pc().loader.parseHtml(text,
              base), target, noSer));
          case JSON -> ctx.output("result", XProcDocument.of(p, ctx.pc().loader.parseJson(text,
              params), target, noSer));
          case TEXT -> ctx.output("result", XProcDocument.of(p, src.getNode(), target, props));
          default -> ctx.output("result", XProcDocument.ofBinary(p,
              text.getBytes(target.getCharset(StandardCharsets.UTF_8)), target, noSer));
        }
      }
      default -> {
        byte[] bytes = src.getBinary();
        switch (tk) {
          case XML -> {
            TreeWriter tw = new TreeWriter(p);
            tw.startDocument(src.getBaseURI());
            tw.addStartElement(Ns.c("data"));
            tw.addAttribute(new QName("content-type"), from.toString());
            tw.addAttribute(new QName("encoding"), "base64");
            tw.addText(Base64.getEncoder().encodeToString(bytes));
            tw.addEndElement();
            ctx.output("result", XProcDocument.of(p, tw.endDocument(), target, noSer));
          }
          case OTHER -> ctx.output("result", XProcDocument.ofBinary(p, bytes, target, props));
          default -> ctx.output("result", ctx.pc().loader.parse(bytes, target,
              target.getCharset(StandardCharsets.UTF_8), src.getBaseURI(), params, noSer,
              false));
        }
      }
    }
  }

  private static XdmNode rootElement(XdmNode doc) {
    XdmNode root = null;
    for (XdmNode c : doc.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        if (root != null) {
          return null;
        }
        root = c;
      }
    }
    return root;
  }

  private static XProcDocument decodeData(StepContext ctx, XProcDocument src, XdmNode data,
      MediaType target, Map<QName, XdmValue> props) {
    String ct = data.getAttributeValue(new QName("content-type"));
    if (ct == null) {
      throw XProcException.xc(73, "The c:data element has no content-type attribute");
    }
    MediaType dataType = MediaType.parse(ct);
    if (!dataType.toBaseString().equals(target.toBaseString())) {
      throw XProcException.xc(74, "The content type " + target
          + " differs from the c:data content type " + ct);
    }
    String encoding = data.getAttributeValue(new QName("encoding"));
    if (encoding != null && !"base64".equals(encoding)) {
      throw XProcException.xc(52, "Unsupported encoding: " + encoding);
    }
    byte[] bytes;
    try {
      bytes = Base64.getMimeDecoder().decode(data.getStringValue().trim());
    } catch (IllegalArgumentException e) {
      throw XProcException.xc(72, "Invalid base64 content");
    }
    String charset = data.getAttributeValue(new QName("charset"));
    Charset cs;
    try {
      cs = charset == null ? dataType.getCharset(StandardCharsets.UTF_8)
          : Charset.forName(charset);
    } catch (Exception e) {
      throw XProcException.xc(71, "Unsupported character set: " + charset);
    }
    if (target.isText() || target.isJson() || target.isMarkup()) {
      java.nio.charset.CharsetDecoder dec = cs.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
      try {
        dec.decode(java.nio.ByteBuffer.wrap(bytes));
      } catch (java.nio.charset.CharacterCodingException e) {
        throw XProcException.xc(72, "The c:data content is not valid " + cs.name());
      }
    }
    return ctx.pc().loader.parse(bytes, target, cs, src.getBaseURI(), null, props, false);
  }

  private static XdmValue xmlToJson(StepContext ctx, XProcDocument src) {
    XdmNode root = rootElement(src.getNode());
    if (root != null && root.getNodeName().equals(Ns.c("param-set"))) {
      return paramSet(ctx, root);
    }
    if (root != null && Ns.FN.equals(root.getNodeName().getNamespace())) {
      return ctx.pc().xpath.evaluateForStep("parse-json(xml-to-json($d))", Map.of(), null, null,
          Map.of(new QName("d"), src.getNode()));
    }
    throw XProcException.xc(71, "Cannot cast this XML document to JSON");
  }

  private static XdmValue paramSet(StepContext ctx, XdmNode root) {
    for (XdmNode a : StepSupport.attributes(root)) {
      if (a.getNodeName().getNamespace().isEmpty()) {
        throw XProcException.xd(14, "Unqualified attribute on c:param-set");
      }
    }
    Map<XdmAtomicValue, XdmValue> map = new LinkedHashMap<>();
    for (XdmNode c : root.children()) {
      if (c.getNodeKind() == XdmNodeKind.TEXT && c.getStringValue().isBlank()
          || c.getNodeKind() == XdmNodeKind.COMMENT
          || c.getNodeKind() == XdmNodeKind.PROCESSING_INSTRUCTION) {
        continue;
      }
      if (c.getNodeKind() != XdmNodeKind.ELEMENT || !c.getNodeName().equals(Ns.c("param"))) {
        throw XProcException.xd(18, "A parameter set may only contain c:param elements");
      }
      for (XdmNode a : StepSupport.attributes(c)) {
        String l = a.getNodeName().getLocalName();
        if (a.getNodeName().getNamespace().isEmpty() && !l.equals("name")
            && !l.equals("namespace") && !l.equals("value") && !l.equals("as")) {
          throw XProcException.xd(14, "Unexpected attribute on c:param: " + l);
        }
      }
      String name = c.getAttributeValue(new QName("name"));
      String ns = c.getAttributeValue(new QName("namespace"));
      QName qn;
      Map<String, String> inscope = nl.armatiek.xproc.internal.model.ExprContext.namespacesOf(c);
      if (ns != null) {
        int colon = name.indexOf(':');
        if (colon >= 0) {
          String prefix = name.substring(0, colon);
          if (!ns.equals(inscope.get(prefix))) {
            throw XProcException.xd(25, "Namespace mismatch for parameter " + name);
          }
          qn = new QName(prefix, ns, name.substring(colon + 1));
        } else {
          qn = new QName(ns, name);
        }
      } else {
        qn = QNames.parseEQName(name, inscope, "XD", 61, 69);
      }
      String value = c.getAttributeValue(new QName("value"));
      if (value != null && c.children().iterator().hasNext()) {
        boolean nonEmpty = false;
        for (XdmNode ch : c.children()) {
          nonEmpty = true;
        }
        if (nonEmpty) {
          throw XProcException.xd(81, "c:param with a value attribute must be empty");
        }
      }
      XdmValue v = new XdmAtomicValue(value != null ? value : c.getStringValue());
      String as = c.getAttributeValue(new QName("as"));
      if (as != null) {
        try {
          v = ctx.pc().types.convert(net.sf.saxon.s9api.XdmValue.wrap(
              net.sf.saxon.value.StringValue.makeUntypedAtomic(net.sf.saxon.str.StringView.of(
                  v.itemAt(0).getStringValue()))), as, inscope, null);
        } catch (XProcException e) {
          throw XProcException.xd(82, "Parameter value does not match type " + as);
        }
      }
      map.put(new XdmAtomicValue(qn), v);
    }
    return new XdmMap(map);
  }

  private static XProcDocument serializeToText(StepContext ctx, XProcDocument src,
      MediaType target, Map<QName, XdmValue> props) {
    String text = ctx.pc().serializer.serializeToString(src, null);
    XProcDocument d = XProcDocument.ofText(ctx.processor(), text, target, src.getBaseURI());
    Map<QName, XdmValue> p = new LinkedHashMap<>(props);
    p.put(Ns.CONTENT_TYPE, new XdmAtomicValue(target.toString()));
    return d.withProperties(ctx.processor(), p);
  }

  static String jsonText(StepContext ctx, XdmValue value) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    var ser = ctx.processor().newSerializer(bos);
    ser.setOutputProperty(net.sf.saxon.s9api.Serializer.Property.METHOD, "json");
    try {
      ser.serializeXdmValue(value);
    } catch (net.sf.saxon.s9api.SaxonApiException e) {
      throw XProcException.xc(71, "Cannot serialize JSON: " + e.getMessage());
    }
    return bos.toString(StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------------------

  static void compare(StepContext ctx) {
    XProcDocument a = ctx.input("source");
    XProcDocument b = ctx.input("alternate");
    XdmValue method = ctx.option("method");
    if (method.size() > 0) {
      QName m = ((XdmAtomicValue) method.itemAt(0)).getQNameValue();
      if (!(m.getNamespace().isEmpty() && "deep-equal".equals(m.getLocalName()))) {
        throw XProcException.xc(76, "Unsupported comparison method: " + QNames.display(m));
      }
    }
    MediaType.Kind ka = a.getKind() == MediaType.Kind.HTML ? MediaType.Kind.XML : a.getKind();
    MediaType.Kind kb = b.getKind() == MediaType.Kind.HTML ? MediaType.Kind.XML : b.getKind();
    if (ka != kb) {
      throw XProcException.xc(77, "Documents of types " + a.getContentType().toBaseString()
          + " and " + b.getContentType().toBaseString() + " cannot be compared");
    }
    boolean equal;
    if (a.isBinary() || b.isBinary()) {
      equal = a.isBinary() && b.isBinary() && java.util.Arrays.equals(a.getBinary(),
          b.getBinary());
    } else {
      equal = XmlSteps.deepEqual(ctx.processor(), a.getValue(), b.getValue());
    }
    if (!equal && Xdm.bool(ctx.option("fail-if-not-equal"))) {
      throw XProcException.xc(19, "The documents are not equal");
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), StepSupport.cResult(
        ctx.processor(), Boolean.toString(equal), null), MediaType.XML, (URI) null));
  }

  static void encode(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    String encoding = Xdm.string(ctx.option("encoding"));
    if (!"base64".equals(encoding)) {
      throw XProcException.xc(52, "Unsupported encoding: " + encoding);
    }
    byte[] bytes;
    String charset = null;
    if (src.isBinary()) {
      bytes = src.getBinary();
    } else {
      Map<QName, XdmValue> params = DocumentSerializer.toParams(ctx.option("serialization"));
      Map<QName, XdmValue> merged = DocumentSerializer.merge(params, src);
      XdmValue enc = merged.get(new QName("encoding"));
      charset = enc == null ? "UTF-8" : enc.itemAt(0).getStringValue();
      bytes = ctx.pc().serializer.serializeToBytes(src, params);
    }
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(null);
    tw.addStartElement(Ns.c("data"));
    tw.addAttribute(new QName("content-type"), src.getContentType().toString());
    if (charset != null) {
      tw.addAttribute(new QName("charset"), charset);
    }
    tw.addAttribute(new QName("encoding"), "base64");
    tw.addText(Base64.getEncoder().encodeToString(bytes));
    tw.addEndElement();
    ctx.output("result", XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.XML,
        (URI) null));
  }

  static void jsonJoin(StepContext ctx) {
    List<XProcDocument> docs = ctx.inputs("source");
    String flatten = Xdm.string(ctx.option("flatten-to-depth"));
    int depth;
    if (flatten == null) {
      depth = 0;
    } else if ("unbounded".equals(flatten.trim())) {
      depth = Integer.MAX_VALUE;
    } else {
      try {
        depth = Integer.parseInt(flatten.trim());
        if (depth < 0) {
          throw new NumberFormatException();
        }
      } catch (NumberFormatException e) {
        throw XProcException.xc(119, "Invalid flatten-to-depth: " + flatten);
      }
    }
    if (docs.isEmpty()) {
      return;
    }
    List<XdmValue> members = new ArrayList<>();
    for (XProcDocument d : docs) {
      if (d.isBinary()) {
        throw XProcException.xc(111, "Binary documents cannot be joined");
      }
      XdmValue v = d.getValue();
      if (d.getKind() == MediaType.Kind.JSON && v.size() == 1 && v.itemAt(0) instanceof XdmArray a) {
        flatten(a, depth, members);
      } else {
        members.add(v);
      }
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), new XdmArray(members),
        MediaType.JSON, (URI) null));
  }

  private static void flatten(XdmArray a, int depth, List<XdmValue> out) {
    if (depth == 0) {
      out.add(a);
      return;
    }
    for (XdmValue m : a.asList()) {
      if (m.size() == 1 && m.itemAt(0) instanceof XdmArray inner) {
        flatten(inner, depth - 1, out);
      } else {
        out.add(m);
      }
    }
  }

  static void jsonMerge(StepContext ctx) {
    List<XProcDocument> docs = ctx.inputs("source");
    String duplicates = Xdm.string(ctx.option("duplicates"));
    if (docs.isEmpty()) {
      return;
    }
    var keyExpr = StepSupport.expression(ctx, "key", List.of(StepSupport.P_INDEX));
    List<XdmValue> maps = new ArrayList<>();
    int index = 0;
    for (XProcDocument d : docs) {
      index++;
      if (d.isBinary()) {
        throw XProcException.xc(107, "Binary documents cannot be merged");
      }
      XdmValue v = d.getValue();
      if (d.getKind() == MediaType.Kind.JSON && v.size() == 1 && v.itemAt(0) instanceof XdmMap) {
        maps.add(v);
        continue;
      }
      ctx.pc().registry.register(d);
      XdmValue k = StepSupport.evaluateLenient(keyExpr, d.getItem(), Map.of(StepSupport.P_INDEX,
          new XdmAtomicValue(index)));
      if (k.size() != 1 || k.itemAt(0) instanceof XdmFunctionItem) {
        throw XProcException.xc(110, "The key expression must return a single atomic value or "
            + "node");
      }
      XdmItem ki = k.itemAt(0);
      XdmAtomicValue key = ki instanceof XdmNode n ? new XdmAtomicValue(n.getStringValue())
          : (XdmAtomicValue) ki;
      maps.add(new XdmMap(Map.of(key, v)));
    }
    Map<QName, XdmValue> vars = new LinkedHashMap<>();
    vars.put(new QName("maps"), new XdmValue(maps.stream().map(m -> m.itemAt(0)).toList()));
    vars.put(new QName("dup"), new XdmAtomicValue(duplicates));
    XdmValue result;
    try {
      result = ctx.pc().xpath.evaluateForStep("map:merge($maps, map{'duplicates': $dup})",
          Map.of("map", Ns.MAP), null, null, vars);
    } catch (XProcException e) {
      if (e.getCode().getLocalName().equals("FOJS0003")) {
        throw XProcException.xc(106, "Duplicate key in p:json-merge");
      }
      throw e;
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), result, MediaType.JSON, (URI) null));
  }

  static void load(StepContext ctx) {
    String href = Xdm.string(ctx.option("href"));
    URI uri;
    try {
      uri = new URI(href);
    } catch (java.net.URISyntaxException e) {
      throw XProcException.xd(64, "Invalid URI: " + href);
    }
    if (!uri.isAbsolute()) {
      URI base = ctx.baseUri();
      if (base == null) {
        throw XProcException.xd(64, "Cannot make " + href + " absolute");
      }
      uri = base.resolve(uri);
    }
    String ct = Xdm.string(ctx.option("content-type"));
    Map<QName, XdmValue> params = nl.armatiek.xproc.internal.runtime.Executor.toQNameMap(
        ctx.option("parameters"), ctx.optionNamespaces("parameters"));
    Map<QName, XdmValue> props = nl.armatiek.xproc.internal.runtime.Executor.toQNameMap(
        ctx.option("document-properties"), ctx.optionNamespaces("document-properties"));
    ctx.output("result", ctx.pc().loader.load(uri, ct, params, props));
  }

  static void store(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    String href = Xdm.string(ctx.option("href"));
    URI uri;
    try {
      uri = new URI(href);
    } catch (java.net.URISyntaxException e) {
      throw XProcException.xd(64, "Invalid URI: " + href);
    }
    if (!uri.isAbsolute() && ctx.baseUri() != null) {
      uri = ctx.baseUri().resolve(uri);
    }
    URI target = ctx.pc().mapUri(uri);
    ctx.pc().checkAccess(target);
    Map<QName, XdmValue> params = DocumentSerializer.toParams(ctx.option("serialization"));
    if (!"file".equalsIgnoreCase(target.getScheme())) {
      throw XProcException.xc(50, "Cannot store to " + uri + ": unsupported URI scheme");
    }
    try {
      Path path = Path.of(target);
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      try (OutputStream out = Files.newOutputStream(path)) {
        ctx.pc().serializer.serialize(src, out, params);
      }
    } catch (IOException | IllegalArgumentException e) {
      throw XProcException.xc(50, "Cannot store to " + uri + ": " + e.getMessage());
    }
    ctx.output("result", src);
    ctx.output("result-uri", XProcDocument.of(ctx.processor(), StepSupport.cResult(
        ctx.processor(), uri.toString(), null), MediaType.XML, (URI) null));
  }

  static void urldecode(StepContext ctx) {
    String value = Xdm.string(ctx.option("value"));
    Map<String, List<String>> m = new LinkedHashMap<>();
    if (!value.isEmpty()) {
      for (String pair : value.split("&", -1)) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String name = eq < 0 ? pair : pair.substring(0, eq);
        String v = eq < 0 ? "" : pair.substring(eq + 1);
        try {
          checkEscapes(name);
          checkEscapes(v);
          m.computeIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8), k -> new ArrayList<>())
              .add(URLDecoder.decode(v, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
          throw XProcException.xc(37, "Invalid x-www-form-urlencoded value: " + value);
        }
      }
    }
    Map<XdmAtomicValue, XdmValue> map = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : m.entrySet()) {
      List<XdmItem> items = new ArrayList<>();
      for (String s : e.getValue()) {
        items.add(new XdmAtomicValue(s));
      }
      map.put(new XdmAtomicValue(e.getKey()), new XdmValue(items));
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), new XdmMap(map), MediaType.JSON,
        (URI) null));
  }

  private static void checkEscapes(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '%') {
        if (i + 2 >= s.length() || Character.digit(s.charAt(i + 1), 16) < 0
            || Character.digit(s.charAt(i + 2), 16) < 0) {
          throw new IllegalArgumentException("bad escape");
        }
      }
    }
  }

  static void urlencode(StepContext ctx) {
    XdmMap map = (XdmMap) ctx.option("parameters").itemAt(0);
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<XdmAtomicValue, XdmValue> e : map.entrySet()) {
      for (XdmItem v : e.getValue()) {
        if (!sb.isEmpty()) {
          sb.append('&');
        }
        sb.append(URLEncoder.encode(e.getKey().getStringValue(), StandardCharsets.UTF_8));
        sb.append('=');
        sb.append(URLEncoder.encode(v.getStringValue(), StandardCharsets.UTF_8));
      }
    }
    ctx.output("result", XProcDocument.ofText(ctx.processor(), sb.toString(), MediaType.TEXT,
        null));
  }

  static void splitSequence(StepContext ctx) {
    List<XProcDocument> docs = ctx.inputs("source");
    boolean initialOnly = Xdm.bool(ctx.option("initial-only"));
    List<XdmValue> results;
    try {
      results = XmlSteps.evaluateWithPosition(ctx, "test", docs);
    } catch (XProcException e) {
      throw XProcException.xc(150, "Error evaluating test expression: " + e.getMessage(), e);
    }
    boolean failed = false;
    for (int i = 0; i < docs.size(); i++) {
      boolean match;
      try {
        match = ebv(ctx, results.get(i));
      } catch (XProcException e) {
        throw XProcException.xc(150, "Error evaluating test expression: " + e.getMessage(), e);
      }
      if (failed || !match) {
        failed = failed || initialOnly;
        ctx.output("not-matched", docs.get(i));
      } else {
        ctx.output("matched", docs.get(i));
      }
    }
  }

  static boolean ebv(StepContext ctx, XdmValue v) {
    return Xdm.bool(ctx.pc().xpath.evaluateForStep("boolean($v)", Map.of(), null, null,
        Map.of(new QName("v"), v)));
  }
}
