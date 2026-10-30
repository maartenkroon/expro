package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.util.Documents;
import nl.armatiek.xproc.internal.util.QNames;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;

/** Steps that manipulate XML documents. */
final class XmlSteps {

  private XmlSteps() {
  }

  static void register(StepRegistry r) {
    r.register("add-attribute", () -> XmlSteps::addAttribute);
    r.register("add-xml-base", () -> XmlSteps::addXmlBase);
    r.register("delete", () -> XmlSteps::delete);
    r.register("insert", () -> XmlSteps::insert);
    r.register("label-elements", () -> XmlSteps::labelElements);
    r.register("make-absolute-uris", () -> XmlSteps::makeAbsoluteUris);
    r.register("namespace-delete", () -> XmlSteps::namespaceDelete);
    r.register("namespace-rename", () -> XmlSteps::namespaceRename);
    r.register("pack", () -> XmlSteps::pack);
    r.register("rename", () -> XmlSteps::rename);
    r.register("replace", () -> XmlSteps::replace);
    r.register("set-attributes", () -> XmlSteps::setAttributes);
    r.register("string-replace", () -> XmlSteps::stringReplace);
    r.register("unwrap", () -> XmlSteps::unwrap);
    r.register("uuid", () -> XmlSteps::uuid);
    r.register("hash", () -> XmlSteps::hash);
    r.register("wrap", () -> XmlSteps::wrap);
    r.register("wrap-sequence", () -> XmlSteps::wrapSequence);
    r.register("filter", () -> XmlSteps::filter);
  }

  // -----------------------------------------------------------------------------------------

  static void addAttribute(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    QName name = ctx.option("attribute-name").itemAt(0) instanceof XdmAtomicValue av
        ? av.getQNameValue() : null;
    StepSupport.checkAttributeName(name);
    String value = Xdm.string(ctx.option("attribute-value"));
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void elementMatched(XdmNode e) {
        tw.addStartElement(e);
        for (XdmNode a : StepSupport.attributes(e)) {
          if (matches(a)) {
            throw notAllowed(a);
          }
          if (!a.getNodeName().equals(name)) {
            tw.addAttribute(a);
          }
        }
        tw.addAttribute(name, value);
        children(e);
        tw.addEndElement();
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void addXmlBase(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    boolean all = Xdm.bool(ctx.option("all"));
    boolean relative = Xdm.bool(ctx.option("relative"));
    if (all && relative) {
      throw XProcException.xc(58, "The all and relative options cannot both be true");
    }
    XdmNode doc = src.getNode();
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(doc.getBaseURI());
    for (XdmNode c : doc.children()) {
      addXmlBase(tw, c, doc.getBaseURI(), all, relative, true);
    }
    ctx.output("result", StepSupport.result(ctx, src, tw.endDocument()));
  }

  private static void addXmlBase(TreeWriter tw, XdmNode n, URI parentBase, boolean all,
      boolean relative, boolean top) {
    if (n.getNodeKind() != XdmNodeKind.ELEMENT) {
      tw.addSubtree(n);
      return;
    }
    URI base = n.getBaseURI();
    tw.addStartElement(n);
    QName xmlBase = Ns.xml("base");
    for (XdmNode a : StepSupport.attributes(n)) {
      if (!a.getNodeName().equals(xmlBase)) {
        tw.addAttribute(a);
      }
    }
    if (top) {
      if (base != null) {
        tw.addAttribute(xmlBase, base.toString());
      }
    } else if (all) {
      if (base != null) {
        tw.addAttribute(xmlBase, base.toString());
      }
    } else if (base != null && !base.equals(parentBase)) {
      tw.addAttribute(xmlBase, relative && parentBase != null ? relativize(parentBase, base)
          : base.toString());
    }
    for (XdmNode c : n.children()) {
      addXmlBase(tw, c, base, all, relative, false);
    }
    tw.addEndElement();
  }

  /** A relative reference that resolves against base to target. */
  static String relativize(URI base, URI target) {
    if (base.getScheme() == null || !base.getScheme().equalsIgnoreCase(target.getScheme())
        || !java.util.Objects.equals(base.getRawAuthority(), target.getRawAuthority())
        || base.isOpaque() || target.isOpaque()) {
      return target.toString();
    }
    String bp = base.getRawPath() == null ? "" : base.getRawPath();
    String tp = target.getRawPath() == null ? "" : target.getRawPath();
    String bdir = bp.substring(0, bp.lastIndexOf('/') + 1);
    String[] bs = bdir.split("/", -1);
    String[] ts = tp.split("/", -1);
    int i = 0;
    while (i < bs.length - 1 && i < ts.length - 1 && bs[i].equals(ts[i])) {
      i++;
    }
    StringBuilder sb = new StringBuilder();
    for (int j = i; j < bs.length - 1; j++) {
      sb.append("../");
    }
    for (int j = i; j < ts.length; j++) {
      sb.append(ts[j]);
      if (j < ts.length - 1) {
        sb.append('/');
      }
    }
    String rel = sb.toString();
    if (target.getRawQuery() != null) {
      rel += "?" + target.getRawQuery();
    }
    if (target.getRawFragment() != null) {
      rel += "#" + target.getRawFragment();
    }
    if (rel.isEmpty()) {
      rel = tp.isEmpty() ? "" : ts[ts.length - 1];
    }
    return rel;
  }

  static void delete(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void elementMatched(XdmNode e) {
      }

      @Override
      protected void attributeMatched(XdmNode owner, XdmNode attr) {
      }

      @Override
      protected void otherMatched(XdmNode n) {
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void insert(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    List<XProcDocument> insertion = ctx.inputs("insertion");
    String position = Xdm.string(ctx.option("position"));
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      void insertAll() {
        for (XProcDocument d : insertion) {
          tw.addChildren(d.getNode());
        }
      }

      @Override
      protected void documentMatched(XdmNode doc) {
        switch (position) {
          case "first-child" -> {
            insertAll();
            children(doc);
          }
          case "last-child" -> {
            children(doc);
            insertAll();
          }
          default -> throw XProcException.xc(24, "Cannot insert before or after the document "
              + "node");
        }
      }

      @Override
      protected void elementMatched(XdmNode e) {
        switch (position) {
          case "first-child" -> {
            tw.addStartElement(e);
            attributes(e);
            insertAll();
            children(e);
            tw.addEndElement();
          }
          case "last-child" -> {
            tw.addStartElement(e);
            attributes(e);
            children(e);
            insertAll();
            tw.addEndElement();
          }
          case "before" -> {
            insertAll();
            element(e);
          }
          default -> {
            element(e);
            insertAll();
          }
        }
      }

      @Override
      protected void otherMatched(XdmNode n) {
        switch (position) {
          case "before" -> {
            insertAll();
            tw.addSubtree(n);
          }
          case "after" -> {
            tw.addSubtree(n);
            insertAll();
          }
          default -> throw XProcException.xc(25, "Cannot insert as child of a "
              + n.getNodeKind().toString().toLowerCase() + " node");
        }
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void labelElements(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    QName attrName = ((XdmAtomicValue) ctx.option("attribute").itemAt(0)).getQNameValue();
    StepSupport.checkAttributeName(attrName);
    boolean replace = Xdm.bool(ctx.option("replace"));
    XPathExecutable label = StepSupport.expression(ctx, "label",
        List.of(StepSupport.P_INDEX));
    int[] index = {0};
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void elementMatched(XdmNode e) {
        index[0]++;
        Map<QName, XdmValue> vars = Map.of(StepSupport.P_INDEX, new XdmAtomicValue(index[0]));
        String value = InlineString.of(StepSupport.evaluateLenient(label, e, vars));
        tw.addStartElement(e);
        boolean exists = false;
        for (XdmNode a : StepSupport.attributes(e)) {
          if (matches(a)) {
            throw notAllowed(a);
          }
          if (a.getNodeName().equals(attrName)) {
            exists = true;
            if (!replace) {
              tw.addAttribute(a);
            }
          } else {
            tw.addAttribute(a);
          }
        }
        if (!exists || replace) {
          tw.addAttribute(attrName, value);
        }
        children(e);
        tw.addEndElement();
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  /** String value of a sequence (items joined with spaces). */
  static final class InlineString {
    static String of(XdmValue v) {
      StringBuilder sb = new StringBuilder();
      for (XdmItem i : v) {
        if (!sb.isEmpty()) {
          sb.append(' ');
        }
        sb.append(i.getStringValue());
      }
      return sb.toString();
    }
  }

  static void makeAbsoluteUris(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    String baseOpt = Xdm.string(ctx.option("base-uri"));
    URI fixedBase = baseOpt == null ? null : URI.create(baseOpt);
    if (fixedBase != null && !fixedBase.isAbsolute()) {
      throw XProcException.xd(64, "Base URI is not absolute: " + baseOpt);
    }
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      String resolve(String value, URI base) {
        URI b = fixedBase != null ? fixedBase : base;
        if (b == null) {
          return value;
        }
        try {
          return b.resolve(new URI(value.trim())).toString();
        } catch (Exception e) {
          return value;
        }
      }

      @Override
      protected void elementMatched(XdmNode e) {
        tw.addStartElement(e);
        attributes(e);
        tw.addText(resolve(e.getStringValue(), e.getBaseURI()));
        tw.addEndElement();
      }

      @Override
      protected void attributeMatched(XdmNode owner, XdmNode attr) {
        tw.addAttribute(attr.getNodeName(), resolve(attr.getStringValue(), owner.getBaseURI()));
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void namespaceDelete(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    Map<String, String> ns = ctx.optionNamespaces("prefixes");
    Set<String> uris = new HashSet<>();
    for (String p : Xdm.string(ctx.option("prefixes")).trim().split("\\s+")) {
      if (p.isEmpty()) {
        continue;
      }
      String uri = "#default".equals(p) ? ns.get("") : ns.get(p);
      if (uri == null) {
        throw XProcException.xc(108, "Prefix '" + p + "' is not in scope");
      }
      uris.add(uri);
    }
    TreeWriter tw = new TreeWriter(ctx.processor());
    XdmNode doc = src.getNode();
    tw.startDocument(doc.getBaseURI());
    for (XdmNode c : doc.children()) {
      renameNamespaces(tw, c, uri -> uris.contains(uri) ? "" : null, true, true, 109);
    }
    ctx.output("result", StepSupport.result(ctx, src, tw.endDocument()));
  }

  /**
   * Copies a node, mapping namespace URIs. The mapper returns the new URI ("" for none) or
   * null for unchanged.
   */
  private static void renameNamespaces(TreeWriter tw, XdmNode n,
      java.util.function.Function<String, String> mapper, boolean elements, boolean attributes,
      int conflictError) {
    if (n.getNodeKind() != XdmNodeKind.ELEMENT) {
      tw.addSubtree(n);
      return;
    }
    QName name = n.getNodeName();
    String mapped = mapper.apply(name.getNamespace());
    QName newName = name;
    if (elements && mapped != null) {
      newName = mapped.isEmpty() ? new QName(name.getLocalName())
          : new QName(name.getPrefix(), mapped, name.getLocalName());
    }
    tw.addStartElement(newName);
    for (XdmNode nsNode : n.axisIterator(Axis.NAMESPACE).stream().toList()) {
      String prefix = nsNode.getNodeName() == null ? "" : nsNode.getNodeName().getLocalName();
      String uri = nsNode.getStringValue();
      if ("xml".equals(prefix)) {
        continue;
      }
      String m = mapper.apply(uri);
      if (m == null) {
        if (!(prefix.equals(newName.getPrefix()) && !uri.equals(newName.getNamespace()))) {
          tw.addNamespace(prefix, uri);
        }
      } else if (!m.isEmpty()) {
        if (!(prefix.equals(newName.getPrefix()) && !m.equals(newName.getNamespace()))) {
          tw.addNamespace(prefix, m);
        }
      }
    }
    Set<QName> seen = new HashSet<>();
    for (XdmNode a : StepSupport.attributes(n)) {
      QName an = a.getNodeName();
      QName nn = an;
      if (attributes) {
        String am = an.getNamespace().isEmpty() && !elementsOnlyNoNs(mapper) ? null
            : mapper.apply(an.getNamespace());
        if (am != null) {
          if (am.isEmpty()) {
            nn = new QName(an.getLocalName());
          } else {
            String prefix = an.getPrefix().isEmpty() ? "_1" : an.getPrefix();
            nn = new QName(prefix, am, an.getLocalName());
          }
        }
      }
      if (!seen.add(new QName(nn.getNamespace(), nn.getLocalName()))) {
        throw XProcException.xc(conflictError, "Duplicate attribute " + QNames.display(nn)
            + " after namespace change");
      }
      tw.addAttribute(nn, a.getStringValue());
    }
    for (XdmNode c : n.children()) {
      renameNamespaces(tw, c, mapper, elements, attributes, conflictError);
    }
    tw.addEndElement();
  }

  private static boolean elementsOnlyNoNs(java.util.function.Function<String, String> mapper) {
    return mapper.apply("") != null;
  }

  static void namespaceRename(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    String from = Xdm.string(ctx.option("from"));
    String to = Xdm.string(ctx.option("to"));
    String applyTo = Xdm.string(ctx.option("apply-to"));
    from = from == null ? "" : from;
    to = to == null ? "" : to;
    for (String u : new String[] {from, to}) {
      if (Ns.XML.equals(u) || Ns.XMLNS.equals(u)) {
        throw XProcException.xc(14, "The XML and XMLNS namespaces cannot be renamed");
      }
    }
    if (from.equals(to)) {
      ctx.output("result", src);
      return;
    }
    String f = from;
    String t = to;
    boolean elements = !"attributes".equals(applyTo);
    boolean attributes = !"elements".equals(applyTo);
    TreeWriter tw = new TreeWriter(ctx.processor());
    XdmNode doc = src.getNode();
    tw.startDocument(doc.getBaseURI());
    for (XdmNode c : doc.children()) {
      renameNamespaces(tw, c, uri -> uri.equals(f) ? t : null, elements, attributes, 92);
    }
    ctx.output("result", StepSupport.result(ctx, src, tw.endDocument()));
  }

  static void pack(StepContext ctx) {
    List<XProcDocument> source = ctx.inputs("source");
    List<XProcDocument> alternate = ctx.inputs("alternate");
    QName wrapper = ((XdmAtomicValue) ctx.option("wrapper").itemAt(0)).getQNameValue();
    Map<QName, String> attrs = StepSupport.attributeMap(ctx, "attributes");
    int n = Math.max(source.size(), alternate.size());
    for (int i = 0; i < n; i++) {
      TreeWriter tw = new TreeWriter(ctx.processor());
      String xmlBase = attrs.get(Ns.xml("base"));
      tw.startDocument(xmlBase == null ? null : URI.create(xmlBase));
      tw.addStartElement(wrapper);
      for (Map.Entry<QName, String> e : attrs.entrySet()) {
        tw.addAttribute(e.getKey(), e.getValue());
      }
      if (i < source.size()) {
        tw.addChildren(source.get(i).getNode());
      }
      if (i < alternate.size()) {
        tw.addChildren(alternate.get(i).getNode());
      }
      tw.addEndElement();
      ctx.output("result", XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.XML,
          (URI) null));
    }
  }

  static void rename(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    QName newName = ((XdmAtomicValue) ctx.option("new-name").itemAt(0)).getQNameValue();
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void elementMatched(XdmNode e) {
        tw.addStartElement(e, newName);
        attributes(e);
        children(e);
        tw.addEndElement();
      }

      @Override
      protected void attributes(XdmNode e) {
        List<XdmNode> attrs = StepSupport.attributes(e);
        XdmNode matched = null;
        for (XdmNode a : attrs) {
          if (matches(a)) {
            if (matched != null) {
              throw XProcException.xc(23, "The pattern matches more than one attribute on "
                  + "an element");
            }
            matched = a;
          }
        }
        for (XdmNode a : attrs) {
          if (a == matched || a.equals(matched)) {
            tw.addAttribute(newName, a.getStringValue());
          } else if (matched == null || !a.getNodeName().equals(newName)) {
            tw.addAttribute(a);
          }
        }
      }

      @Override
      protected void otherMatched(XdmNode n) {
        if (n.getNodeKind() == XdmNodeKind.PROCESSING_INSTRUCTION) {
          if (!newName.getNamespace().isEmpty()) {
            throw XProcException.xc(13, "A processing instruction target cannot have a "
                + "namespace");
          }
          tw.addPI(newName.getLocalName(), n.getStringValue());
          return;
        }
        throw notAllowed(n);
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void replace(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    XProcDocument replacement = ctx.input("replacement");
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void documentMatched(XdmNode doc) {
        tw.addChildren(replacement.getNode());
      }

      @Override
      protected void elementMatched(XdmNode e) {
        tw.addChildren(replacement.getNode());
      }

      @Override
      protected void otherMatched(XdmNode n) {
        tw.addChildren(replacement.getNode());
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void setAttributes(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    Map<QName, String> attrs = StepSupport.attributeMap(ctx, "attributes");
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void elementMatched(XdmNode e) {
        tw.addStartElement(e);
        for (XdmNode a : StepSupport.attributes(e)) {
          if (matches(a)) {
            throw notAllowed(a);
          }
          if (!attrs.containsKey(a.getNodeName())) {
            tw.addAttribute(a);
          }
        }
        for (Map.Entry<QName, String> en : attrs.entrySet()) {
          tw.addAttribute(en.getKey(), en.getValue());
        }
        children(e);
        tw.addEndElement();
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  /** A rewriter that replaces matched nodes by a string. */
  private abstract static class StringRewriter extends Rewriter {
    StringRewriter(Processor p, XPathExecutable pattern) {
      super(p, pattern);
    }

    abstract String value(XdmNode node);

    @Override
    protected void documentMatched(XdmNode doc) {
      tw.addText(value(doc));
    }

    @Override
    protected void elementMatched(XdmNode e) {
      tw.addText(value(e));
    }

    @Override
    protected void attributeMatched(XdmNode owner, XdmNode attr) {
      tw.addAttribute(attr.getNodeName(), value(attr));
    }

    @Override
    protected void otherMatched(XdmNode n) {
      tw.addText(value(n));
    }
  }

  static void stringReplace(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    XPathExecutable replace = StepSupport.expression(ctx, "replace", List.of());
    Rewriter rw = new StringRewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      String value(XdmNode node) {
        return InlineString.of(StepSupport.evaluate(replace, node, null));
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void unwrap(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    Rewriter rw = new Rewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      protected void documentMatched(XdmNode doc) {
        children(doc);
      }

      @Override
      protected void elementMatched(XdmNode e) {
        for (XdmNode a : StepSupport.attributes(e)) {
          if (matches(a)) {
            throw notAllowed(a);
          }
        }
        children(e);
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void uuid(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    long version = Xdm.integer(ctx.option("version"), 4);
    if (version != 4) {
      throw XProcException.xc(60, "Unsupported UUID version: " + version);
    }
    String uuid = UUID.randomUUID().toString();
    Rewriter rw = new StringRewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      String value(XdmNode node) {
        return uuid;
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static void hash(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    QName algorithm = ((XdmAtomicValue) ctx.option("algorithm").itemAt(0)).getQNameValue();
    String version = Xdm.string(ctx.option("version"));
    String value = Xdm.string(ctx.option("value"));
    String hash = computeHash(algorithm, version, value);
    Rewriter rw = new StringRewriter(ctx.processor(), StepSupport.pattern(ctx, "match")) {
      @Override
      String value(XdmNode node) {
        return hash;
      }
    };
    ctx.output("result", StepSupport.result(ctx, src, rw.run(src.getNode())));
  }

  static String computeHash(QName algorithm, String version, String value) {
    if (!algorithm.getNamespace().isEmpty()) {
      throw XProcException.xc(36, "Unsupported hash algorithm: " + QNames.display(algorithm));
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    try {
      switch (algorithm.getLocalName()) {
        case "crc" -> {
          if (version != null && !"32".equals(version)) {
            throw XProcException.xc(36, "Unsupported CRC version: " + version);
          }
          CRC32 crc = new CRC32();
          crc.update(bytes);
          return Long.toHexString(crc.getValue());
        }
        case "md" -> {
          if (version != null && !"5".equals(version)) {
            throw XProcException.xc(36, "Unsupported MD version: " + version);
          }
          return hex(MessageDigest.getInstance("MD5").digest(bytes));
        }
        case "sha" -> {
          String v = version == null ? "1" : version;
          String alg = switch (v) {
            case "1" -> "SHA-1";
            case "224" -> "SHA-224";
            case "256" -> "SHA-256";
            case "384" -> "SHA-384";
            case "512" -> "SHA-512";
            default -> throw XProcException.xc(36, "Unsupported SHA version: " + v);
          };
          return hex(MessageDigest.getInstance(alg).digest(bytes));
        }
        default -> throw XProcException.xc(36, "Unsupported hash algorithm: "
            + algorithm.getLocalName());
      }
    } catch (java.security.NoSuchAlgorithmException e) {
      throw XProcException.xc(36, "Unsupported hash algorithm: " + e.getMessage());
    }
  }

  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) {
      sb.append(String.format("%02x", x & 0xff));
    }
    return sb.toString();
  }

  static void wrap(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    QName wrapper = ((XdmAtomicValue) ctx.option("wrapper").itemAt(0)).getQNameValue();
    Map<QName, String> attrs = StepSupport.attributeMap(ctx, "attributes");
    String ga = Xdm.string(ctx.option("group-adjacent"));
    XPathExecutable groupAdjacent = ga == null ? null
        : StepSupport.expression(ctx, "group-adjacent", List.of());
    Processor processor = ctx.processor();
    Rewriter rw = new Rewriter(processor, StepSupport.pattern(ctx, "match")) {
      void startWrapper() {
        tw.addStartElement(wrapper);
        for (Map.Entry<QName, String> e : attrs.entrySet()) {
          tw.addAttribute(e.getKey(), e.getValue());
        }
      }

      @Override
      protected void documentMatched(XdmNode doc) {
        startWrapper();
        children(doc);
        tw.addEndElement();
      }

      @Override
      protected void attributeMatched(XdmNode owner, XdmNode attr) {
        throw notAllowed(attr);
      }

      void processMatched(XdmNode n) {
        if (n.getNodeKind() == XdmNodeKind.ELEMENT) {
          element(n);
        } else {
          tw.addSubtree(n);
        }
      }

      @Override
      protected void children(XdmNode node) {
        List<XdmNode> kids = new ArrayList<>();
        for (XdmNode c : node.children()) {
          kids.add(c);
        }
        int i = 0;
        while (i < kids.size()) {
          XdmNode c = kids.get(i);
          if (!matches(c)) {
            node(c);
            i++;
            continue;
          }
          startWrapper();
          processMatched(c);
          i++;
          if (groupAdjacent != null) {
            XdmValue key = StepSupport.evaluate(groupAdjacent, c, null);
            List<XdmNode> pending = new ArrayList<>();
            while (i < kids.size()) {
              XdmNode d = kids.get(i);
              if (matches(d)) {
                XdmValue k2 = StepSupport.evaluate(groupAdjacent, d, null);
                if (!deepEqual(processor, key, k2)) {
                  break;
                }
                for (XdmNode p : pending) {
                  tw.addSubtree(p);
                }
                pending.clear();
                processMatched(d);
                i++;
              } else if (isIgnorable(d)) {
                pending.add(d);
                i++;
              } else {
                break;
              }
            }
            i -= pending.size();
          }
          tw.addEndElement();
        }
      }

      @Override
      protected void node(XdmNode n) {
        if (n.getNodeKind() == XdmNodeKind.ELEMENT) {
          element(n);
        } else {
          tw.addSubtree(n);
        }
      }

      @Override
      protected void elementMatched(XdmNode e) {
        element(e);
      }

      @Override
      protected void otherMatched(XdmNode n) {
        tw.addSubtree(n);
      }
    };
    XdmNode result = rw.run(src.getNode());
    ctx.output("result", src.withValue(processor, result, MediaType.XML));
  }

  private static boolean isIgnorable(XdmNode n) {
    return switch (n.getNodeKind()) {
      case COMMENT, PROCESSING_INSTRUCTION -> true;
      case TEXT -> n.getStringValue().isBlank();
      default -> false;
    };
  }

  static boolean deepEqual(Processor processor, XdmValue a, XdmValue b) {
    try {
      var xc = processor.newXPathCompiler();
      xc.declareVariable(new QName("a"));
      xc.declareVariable(new QName("b"));
      var sel = xc.compile("deep-equal($a, $b)").load();
      sel.setVariable(new QName("a"), a);
      sel.setVariable(new QName("b"), b);
      return sel.effectiveBooleanValue();
    } catch (net.sf.saxon.s9api.SaxonApiException e) {
      return false;
    }
  }

  static final QName DOCS = new QName(Ns.EXT, "docs");
  static final QName POS = new QName(Ns.EXT, "pos");

  /**
   * Evaluates an expression for each document of a sequence with the context position and size
   * set to the position of the document in the sequence.
   */
  static List<XdmValue> evaluateWithPosition(StepContext ctx, String option,
      List<XProcDocument> docs) {
    String text = Xdm.string(ctx.option(option));
    String wrapped = "let $__d := $Q{" + Ns.EXT + "}docs return $__d ! (if (position() eq $Q{"
        + Ns.EXT + "}pos) then (" + text + "\n) else ())";
    Map<String, String> ns = ctx.optionNamespaces(option);
    XPathExecutable exec;
    try {
      exec = ctx.pc().xpath.compileForStep(wrapped, ns, ctx.baseUri(), List.of(DOCS, POS),
          false);
    } catch (XProcException e) {
      // report errors against the original expression
      ctx.pc().xpath.compileForStep(text, ns, ctx.baseUri(), List.of(), false);
      throw e;
    }
    List<XdmItem> items = new ArrayList<>();
    for (XProcDocument d : docs) {
      ctx.pc().registry.register(d);
      items.add(d.getItem() == null ? d.getNode() : d.getItem());
    }
    XdmValue seq = new XdmValue(items);
    List<XdmValue> results = new ArrayList<>();
    for (int i = 0; i < docs.size(); i++) {
      Map<QName, XdmValue> vars = new LinkedHashMap<>();
      vars.put(DOCS, seq);
      vars.put(POS, new XdmAtomicValue(i + 1));
      results.add(StepSupport.evaluateLenient(exec, null, vars));
    }
    return results;
  }

  static void wrapSequence(StepContext ctx) {
    List<XProcDocument> docs = ctx.inputs("source");
    QName wrapper = ((XdmAtomicValue) ctx.option("wrapper").itemAt(0)).getQNameValue();
    Map<QName, String> attrs = StepSupport.attributeMap(ctx, "attributes");
    String ga = Xdm.string(ctx.option("group-adjacent"));
    List<List<XProcDocument>> groups = new ArrayList<>();
    if (ga == null) {
      groups.add(docs);
    } else {
      List<XdmValue> keys = evaluateWithPosition(ctx, "group-adjacent", docs);
      XdmValue current = null;
      List<XProcDocument> group = null;
      for (int i = 0; i < docs.size(); i++) {
        if (group == null || !deepEqual(ctx.processor(), current, keys.get(i))) {
          group = new ArrayList<>();
          groups.add(group);
          current = keys.get(i);
        }
        group.add(docs.get(i));
      }
    }
    String xmlBase = attrs.get(Ns.xml("base"));
    for (List<XProcDocument> g : groups) {
      TreeWriter tw = new TreeWriter(ctx.processor());
      tw.startDocument(xmlBase == null ? null : URI.create(xmlBase));
      tw.addStartElement(wrapper);
      for (Map.Entry<QName, String> e : attrs.entrySet()) {
        tw.addAttribute(e.getKey(), e.getValue());
      }
      for (XProcDocument d : g) {
        tw.addChildren(d.getNode());
      }
      tw.addEndElement();
      ctx.output("result", XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.XML,
          (URI) null));
    }
  }

  static void filter(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    XPathExecutable select = StepSupport.expression(ctx, "select", List.of());
    ctx.pc().registry.register(src);
    XdmValue v = StepSupport.evaluate(select, src.getNode(), null);
    for (XdmItem item : v) {
      ctx.output("result", Documents.fromItem(ctx.processor(), item, src,
          XProcException.xc(23, "The select expression selected an attribute or function")));
    }
  }
}
