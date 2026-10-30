package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmArray;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentLoader;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.runtime.Executor;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;

/** p:archive, p:archive-manifest, p:unarchive, p:compress and p:uncompress. */
final class ArchiveSteps {

  private ArchiveSteps() {
  }

  static void register(StepRegistry r) {
    r.register("archive", () -> ArchiveSteps::archive);
    r.register("archive-manifest", () -> ArchiveSteps::archiveManifest);
    r.register("unarchive", () -> ArchiveSteps::unarchive);
    r.register("compress", () -> ArchiveSteps::compress);
    r.register("uncompress", () -> ArchiveSteps::uncompress);
  }

  private record Entry(String name, byte[] data, long time, String method, String comment) {
    Entry(String name, byte[] data, long time) {
      this(name, data, time, null, null);
    }
  }

  // ---------------------------------------------------------------------------------------
  // helpers

  private static String format(StepContext ctx, XProcDocument doc, String option) {
    XdmValue f = ctx.option(option);
    if (f.size() > 0) {
      QName q = ((XdmAtomicValue) f.itemAt(0)).getQNameValue();
      if (!q.getNamespace().isEmpty() || !"zip".equals(q.getLocalName())) {
        throw XProcException.xc(85, "Unsupported archive format: " + q);
      }
      if (doc != null && !isZip(doc)) {
        throw XProcException.xc(81, "The document is not a ZIP archive");
      }
      return "zip";
    }
    if (doc != null && !isZip(doc)) {
      throw doc.isBinary() ? XProcException.xc(85, "Cannot determine the archive format")
          : XProcException.xc(81, "The document is not an archive");
    }
    return "zip";
  }

  private static boolean isZip(XProcDocument doc) {
    byte[] b = doc.getBinary();
    return b != null && b.length >= 4 && b[0] == 'P' && b[1] == 'K'
        && (b[2] == 3 || b[2] == 5 || b[2] == 7) && (b[3] == 4 || b[3] == 6 || b[3] == 8);
  }

  private static List<Entry> readZip(byte[] bytes) {
    if (bytes == null) {
      throw XProcException.xc(85, "The archive is not a binary document");
    }
    List<Entry> entries = new ArrayList<>();
    java.nio.file.Path tmp = null;
    try {
      tmp = java.nio.file.Files.createTempFile("xproc-zip", ".zip");
      java.nio.file.Files.write(tmp, bytes);
      try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmp.toFile())) {
        var en = zf.entries();
        while (en.hasMoreElements()) {
          ZipEntry ze = en.nextElement();
          if (ze.isDirectory()) {
            continue;
          }
          byte[] data;
          try (var in = zf.getInputStream(ze)) {
            data = in.readAllBytes();
          }
          entries.add(new Entry(ze.getName(), data, ze.getTime(),
              ze.getMethod() == ZipEntry.STORED ? "none" : "deflated", ze.getComment()));
        }
      }
    } catch (IOException e) {
      throw XProcException.xc(85, "Cannot read ZIP archive: " + e.getMessage());
    } finally {
      if (tmp != null) {
        try {
          java.nio.file.Files.deleteIfExists(tmp);
        } catch (IOException e) {
          // ignore
        }
      }
    }
    return entries;
  }

  private static List<String[]> overrides(StepContext ctx) {
    XdmValue v = ctx.option("override-content-types");
    List<String[]> list = new ArrayList<>();
    if (v.size() == 0) {
      return list;
    }
    if (!(v.itemAt(0) instanceof XdmArray outer)) {
      throw XProcException.xc(146, "override-content-types must be an array of arrays");
    }
    for (XdmValue m : outer.asList()) {
      if (m.size() != 1 || !(m.itemAt(0) instanceof XdmArray inner) || inner.arrayLength() != 2) {
        throw XProcException.xc(146, "override-content-types must be an array of arrays of two "
            + "strings");
      }
      String regex = inner.get(0).itemAt(0).getStringValue();
      String ct = inner.get(1).itemAt(0).getStringValue();
      MediaType.parse(ct);
      try {
        ctx.pc().xpath.evaluateForStep("matches('', $r)", Map.of(), null, null,
            Map.of(new QName("r"), new XdmAtomicValue(regex)));
      } catch (XProcException e) {
        throw XProcException.xc(147, "Invalid regular expression: " + regex);
      }
      list.add(new String[] {regex, ct});
    }
    return list;
  }

  private static boolean matches(StepContext ctx, String input, String regex) {
    try {
      return Xdm.bool(ctx.pc().xpath.evaluateForStep("matches($s, $r)", Map.of(), null, null,
          Map.of(new QName("s"), new XdmAtomicValue(input), new QName("r"),
              new XdmAtomicValue(regex))));
    } catch (XProcException e) {
      throw XProcException.xc(147, "Invalid regular expression: " + regex);
    }
  }

  private static String contentTypeFor(StepContext ctx, String name, List<String[]> overrides) {
    for (String[] o : overrides) {
      if (matches(ctx, name, o[0])) {
        return o[1];
      }
    }
    String ct = DocumentLoader.contentTypeForName(name);
    return ct == null ? "application/octet-stream" : ct;
  }

  private static URI entryBase(StepContext ctx, XProcDocument archive, String name) {
    String rel = Xdm.string(ctx.option("relative-to"));
    String base;
    if (rel != null) {
      try {
        new URI(rel);
      } catch (java.net.URISyntaxException e) {
        throw XProcException.xd(64, "Invalid relative-to URI: " + rel);
      }
      base = rel;
    } else {
      URI b = archive.getBaseURI();
      if (b == null) {
        throw XProcException.xc(120, "The archive has no base URI and no relative-to option was "
            + "specified");
      }
      base = b.toString() + "/";
    }
    try {
      return new URI(ctx.pc().urify.urify(name, base));
    } catch (java.net.URISyntaxException e) {
      throw XProcException.xd(64, "Invalid URI for entry " + name);
    }
  }

  // ---------------------------------------------------------------------------------------

  static void unarchive(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    format(ctx, src, "format");
    List<String[]> overrides = overrides(ctx);
    List<String> include = strings(ctx.option("include-filter"));
    List<String> exclude = strings(ctx.option("exclude-filter"));
    for (Entry e : readZip(src.getBinary())) {
      if (!include.isEmpty() && include.stream().noneMatch(r -> matches(ctx, e.name(), r))) {
        continue;
      }
      if (exclude.stream().anyMatch(r -> matches(ctx, e.name(), r))) {
        continue;
      }
      MediaType ct = MediaType.parse(contentTypeFor(ctx, e.name(), overrides));
      URI base = entryBase(ctx, src, e.name());
      Map<QName, XdmValue> props = new LinkedHashMap<>();
      props.put(Ns.BASE_URI, new XdmAtomicValue(base));
      ctx.output("result", ctx.pc().loader.parse(e.data(), ct, ct.getCharset(null), base, null,
          props, true));
    }
  }

  private static List<String> strings(XdmValue v) {
    List<String> l = new ArrayList<>();
    for (var i : v) {
      l.add(i.getStringValue());
    }
    return l;
  }

  static void archiveManifest(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    format(ctx, src, "format");
    List<String[]> overrides = overrides(ctx);
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(null);
    tw.addStartElement(Ns.c("archive"));
    for (Entry e : readZip(src.getBinary())) {
      tw.addStartElement(Ns.c("entry"));
      tw.addAttribute(new QName("name"), e.name());
      tw.addAttribute(new QName("content-type"), contentTypeFor(ctx, e.name(), overrides));
      tw.addAttribute(new QName("href"), entryBase(ctx, src, e.name()).toString());
      if (e.method() != null) {
        tw.addAttribute(new QName("method"), e.method());
      }
      if (e.comment() != null) {
        tw.addAttribute(new QName("comment"), e.comment());
      }
      tw.addEndElement();
    }
    tw.addEndElement();
    ctx.output("result", XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.XML,
        (URI) null));
  }

  static void archive(StepContext ctx) {
    List<XProcDocument> sources = ctx.inputs("source");
    List<XProcDocument> manifests = ctx.inputs("manifest");
    List<XProcDocument> archives = ctx.inputs("archive");
    if (manifests.size() > 1) {
      throw XProcException.xc(112, "More than one manifest document");
    }
    format(ctx, null, "format");
    Map<QName, XdmValue> params = Executor.toQNameMap(ctx.option("parameters"),
        ctx.optionNamespaces("parameters"));
    String command = paramString(params, "command", "update");
    String defaultLevel = paramString(params, "level", "default");
    String defaultMethod = paramString(params, "method", "deflated");
    if (!List.of("update", "create", "freshen", "delete").contains(command)) {
      throw XProcException.xc(79, "Unsupported command: " + command);
    }
    if (!List.of("none", "deflated").contains(defaultMethod)) {
      throw XProcException.xc(79, "Unsupported compression method: " + defaultMethod);
    }
    if (!List.of("smallest", "fastest", "default", "huffman", "none").contains(defaultLevel)) {
      throw XProcException.xc(79, "Unsupported compression level: " + defaultLevel);
    }
    if (archives.size() > 1 || ("delete".equals(command) && archives.size() != 1)) {
      throw XProcException.xc(80, "Unexpected number of archives for command " + command);
    }
    for (XProcDocument a : archives) {
      if (!isZip(a)) {
        throw ctx.option("format").size() > 0 && a.isBinary()
            ? XProcException.xc(85, "The archive cannot be processed as ZIP")
            : XProcException.xc(a.isBinary() ? 85 : 81, "The document on the archive port is "
                + "not a ZIP archive");
      }
    }
    String relTo = Xdm.string(ctx.option("relative-to"));
    if (relTo != null) {
      try {
        if (!new URI(relTo).isAbsolute()) {
          throw XProcException.xd(64, "relative-to is not absolute: " + relTo);
        }
      } catch (java.net.URISyntaxException e) {
        throw XProcException.xd(64, "Invalid relative-to URI: " + relTo);
      }
    }
    Map<String, XProcDocument> byBase = new LinkedHashMap<>();
    for (XProcDocument d : sources) {
      URI b = d.getBaseURI();
      if (b == null) {
        throw XProcException.xc(84, "A document on the source port has no base URI");
      }
      if (byBase.put(b.toString(), d) != null) {
        throw XProcException.xc(84, "Two documents on the source port have the same base URI "
            + b);
      }
    }
    // manifest
    XdmNode manifest;
    if (manifests.isEmpty()) {
      String rel = Xdm.string(ctx.option("relative-to"));
      TreeWriter tw = new TreeWriter(ctx.processor());
      tw.startDocument(null);
      tw.addStartElement(Ns.c("archive"));
      for (XProcDocument d : sources) {
        String href = d.getBaseURI().toString();
        String name;
        if (rel != null && href.startsWith(rel)) {
          name = href.substring(rel.length());
        } else {
          name = d.getBaseURI().getPath() == null ? href : d.getBaseURI().getPath();
        }
        while (name.startsWith("/")) {
          name = name.substring(1);
        }
        tw.addStartElement(Ns.c("entry"));
        tw.addAttribute(new QName("name"), name);
        tw.addAttribute(new QName("href"), href);
        tw.addEndElement();
      }
      tw.addEndElement();
      manifest = tw.endDocument();
    } else {
      manifest = manifests.get(0).getNode();
    }
    XdmNode root = null;
    for (XdmNode c : manifest.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        root = c;
      }
    }
    if (root == null || !root.getNodeName().equals(Ns.c("archive"))) {
      throw XProcException.xc(100, "The manifest must have a c:archive root element");
    }
    Map<String, XdmNode> entries = new LinkedHashMap<>();
    for (XdmNode e : root.children()) {
      if (e.getNodeKind() != XdmNodeKind.ELEMENT) {
        continue;
      }
      if (!e.getNodeName().equals(Ns.c("entry"))) {
        if (Ns.XPROC_STEP.equals(e.getNodeName().getNamespace())) {
          throw XProcException.xc(100, "Unexpected element in manifest: " + e.getNodeName());
        }
        continue;
      }
      String name = e.getAttributeValue(new QName("name"));
      String href = e.getAttributeValue(new QName("href"));
      if (name == null || href == null) {
        throw XProcException.xc(100, "c:entry requires name and href attributes");
      }
      entries.put(name, e);
    }
    List<Entry> existing = archives.isEmpty() ? List.of() : readZip(archives.get(0).getBinary());
    Map<String, Entry> result = new LinkedHashMap<>();
    Map<String, String[]> settings = new LinkedHashMap<>();
    for (Entry e : existing) {
      result.put(e.name(), e);
    }
    if ("delete".equals(command)) {
      for (String name : entries.keySet()) {
        result.remove(name);
      }
    } else {
      for (Map.Entry<String, XdmNode> me : entries.entrySet()) {
        String name = me.getKey();
        if ("freshen".equals(command) && !result.containsKey(name)) {
          continue;
        }
        XdmNode e = me.getValue();
        URI href = resolve(e, e.getAttributeValue(new QName("href")));
        byte[] data;
        XProcDocument doc = byBase.get(href.toString());
        if (doc != null) {
          data = ctx.pc().serializer.serializeToBytes(doc, null);
        } else {
          data = ctx.pc().loader.read(href).bytes();
        }
        result.put(name, new Entry(name, data, System.currentTimeMillis(), null,
            e.getAttributeValue(new QName("comment"))));
        settings.put(name, new String[] {e.getAttributeValue(new QName("method")),
            e.getAttributeValue(new QName("level"))});
      }
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream zout = new ZipOutputStream(bos)) {
      for (Entry e : result.values()) {
        String[] s = settings.getOrDefault(e.name(), new String[2]);
        String method = s[0] == null ? defaultMethod : s[0];
        String level = s[1] == null ? defaultLevel : s[1];
        ZipEntry ze = new ZipEntry(e.name());
        ze.setTime(e.time());
        if (e.comment() != null) {
          ze.setComment(e.comment());
        }
        if (s[0] == null && e.method() != null && !settings.containsKey(e.name())) {
          method = e.method();
        }
        if ("none".equals(method) || "stored".equals(method)) {
          ze.setMethod(ZipEntry.STORED);
          ze.setSize(e.data().length);
          ze.setCompressedSize(e.data().length);
          java.util.zip.CRC32 crc = new java.util.zip.CRC32();
          crc.update(e.data());
          ze.setCrc(crc.getValue());
        } else if ("deflated".equals(method)) {
          ze.setMethod(ZipEntry.DEFLATED);
          zout.setLevel(switch (level) {
            case "smallest" -> Deflater.BEST_COMPRESSION;
            case "fastest" -> Deflater.BEST_SPEED;
            case "huffman" -> Deflater.BEST_SPEED;
            case "none" -> Deflater.NO_COMPRESSION;
            case "default" -> Deflater.DEFAULT_COMPRESSION;
            default -> throw XProcException.xc(79, "Unsupported compression level: " + level);
          });
        } else {
          throw XProcException.xc(79, "Unsupported compression method: " + method);
        }
        zout.putNextEntry(ze);
        zout.write(e.data());
        zout.closeEntry();
      }
    } catch (IOException e) {
      throw XProcException.xd(30, "Cannot create ZIP archive: " + e.getMessage());
    }
    ctx.output("result", XProcDocument.ofBinary(ctx.processor(), bos.toByteArray(),
        MediaType.ZIP, null));
    ctx.output("report", XProcDocument.of(ctx.processor(), manifest, MediaType.XML,
        (URI) null));
  }

  private static URI resolve(XdmNode e, String href) {
    try {
      URI u = new URI(href);
      if (!u.isAbsolute() && e.getBaseURI() != null) {
        u = e.getBaseURI().resolve(u);
      }
      return u;
    } catch (java.net.URISyntaxException ex) {
      throw XProcException.xd(64, "Invalid href in manifest: " + href);
    }
  }

  private static String paramString(Map<QName, XdmValue> params, String name, String dflt) {
    XdmValue v = params.get(new QName(name));
    return v == null || v.size() == 0 ? dflt : v.itemAt(0).getStringValue();
  }

  // ---------------------------------------------------------------------------------------

  static void compress(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    XdmValue f = ctx.option("format");
    QName fmt = ((XdmAtomicValue) f.itemAt(0)).getQNameValue();
    if (!fmt.getNamespace().isEmpty() || !"gzip".equals(fmt.getLocalName())) {
      throw XProcException.xc(202, "Unsupported compression format: " + fmt);
    }
    Map<QName, XdmValue> ser = DocumentSerializer.toParams(ctx.option("serialization"));
    byte[] data = ctx.pc().serializer.serializeToBytes(src, ser);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
      gz.write(data);
    } catch (IOException e) {
      throw XProcException.xc(202, "Compression failed: " + e.getMessage());
    }
    Map<QName, XdmValue> props = new LinkedHashMap<>(src.getProperties());
    props.remove(Ns.SERIALIZATION);
    props.remove(Ns.CONTENT_TYPE);
    ctx.output("result", XProcDocument.ofBinary(ctx.processor(), bos.toByteArray(),
        MediaType.parse("application/gzip"), props));
  }

  static void uncompress(StepContext ctx) {
    XProcDocument src = ctx.input("source");
    XdmValue f = ctx.option("format");
    byte[] bytes = src.isBinary() ? src.getBinary()
        : ctx.pc().serializer.serializeToBytes(src, null);
    if (f.size() > 0) {
      QName fmt = ((XdmAtomicValue) f.itemAt(0)).getQNameValue();
      if (!fmt.getNamespace().isEmpty() || !"gzip".equals(fmt.getLocalName())) {
        throw XProcException.xc(202, "Unsupported compression format: " + fmt);
      }
    }
    if (bytes.length < 2 || (bytes[0] & 0xff) != 0x1f || (bytes[1] & 0xff) != 0x8b) {
      throw XProcException.xc(202, "The document is not in GZIP format");
    }
    byte[] data;
    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      data = gz.readAllBytes();
    } catch (IOException e) {
      throw XProcException.xc(202, "Uncompressing failed: " + e.getMessage());
    }
    MediaType ct = MediaType.parse(Xdm.string(ctx.option("content-type")));
    Map<QName, XdmValue> props = new LinkedHashMap<>(src.getProperties());
    props.remove(Ns.CONTENT_TYPE);
    props.remove(Ns.SERIALIZATION);
    try {
      ctx.output("result", ctx.pc().loader.parse(data, ct, ct.getCharset(StandardCharsets.UTF_8),
          src.getBaseURI(), null, props, false));
    } catch (XProcException e) {
      throw XProcException.xc(201, "Cannot interpret the uncompressed data as " + ct + ": "
          + e.getMessage());
    }
  }
}
