package nl.armatiek.xproc.internal.steps;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmArray;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentLoader;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;

/**
 * The steps of the XProc 3.1 File Steps specification. Only the file: URI scheme is supported.
 * With fail-on-error set to false, errors are reported as a c:error element whose code and
 * type attributes give the error code and the step type in Clark notation ({uri}local) and
 * whose content is the error message. Successful steps return the (resolved) URI given in their
 * href or target option.
 */
final class FileSteps {

  private static final QName XML_BASE = new QName("xml", Ns.XML, "base");
  private static final QName NAME = new QName("name");

  private FileSteps() {
  }

  static void register(StepRegistry r) {
    r.register("directory-list", () -> FileSteps::directoryList);
    r.register("file-copy", () -> ctx -> guarded(ctx, () -> copy(ctx)));
    r.register("file-delete", () -> ctx -> guarded(ctx, () -> delete(ctx)));
    r.register("file-info", () -> ctx -> guarded(ctx, () -> info(ctx)));
    r.register("file-mkdir", () -> ctx -> guarded(ctx, () -> mkdir(ctx)));
    r.register("file-move", () -> ctx -> guarded(ctx, () -> move(ctx)));
    r.register("file-create-tempfile", () -> ctx -> guarded(ctx, () -> tempfile(ctx)));
    r.register("file-touch", () -> ctx -> guarded(ctx, () -> touch(ctx)));
  }

  /** The error codes of a step: unsupported scheme and access restrictions. */
  private record Codes(int scheme, int access) {
  }

  // =========================================================================================
  // Common

  /** Runs a step body, turning errors into a c:error result when fail-on-error is false. */
  private static void guarded(StepContext ctx, java.util.function.Supplier<XdmNode> body) {
    XdmNode result;
    try {
      result = body.get();
    } catch (XProcException e) {
      if (Xdm.bool(ctx.option("fail-on-error"))) {
        throw e;
      }
      TreeWriter tw = new TreeWriter(ctx.processor());
      tw.startDocument(null);
      tw.addStartElement(Ns.c("error"));
      tw.addAttribute(new QName("code"), "{" + e.getCode().getNamespace() + "}"
          + e.getCode().getLocalName());
      tw.addAttribute(new QName("type"), "{" + ctx.stepType().getNamespace() + "}"
          + ctx.stepType().getLocalName());
      if (e.getMessage() != null) {
        tw.addText(e.getMessage());
      }
      tw.addEndElement();
      result = tw.endDocument();
    }
    ctx.output("result", XProcDocument.of(ctx.processor(), result, MediaType.XML, (URI) null));
  }

  /** Resolves a URI option and checks that it is a file: URI that may be accessed. */
  private static URI uri(StepContext ctx, String option, Codes codes) {
    String text = Xdm.string(ctx.option(option));
    URI uri;
    try {
      uri = new URI(text);
    } catch (URISyntaxException e) {
      throw XProcException.xd(64, "Invalid URI: " + text);
    }
    if (!uri.isAbsolute()) {
      URI base = ctx.baseUri();
      if (base == null || !base.isAbsolute()) {
        throw XProcException.xd(64, "Cannot resolve " + text + ": no absolute base URI");
      }
      uri = base.resolve(uri);
    }
    uri = ctx.pc().mapUri(uri);
    if (!"file".equalsIgnoreCase(uri.getScheme())) {
      throw XProcException.xc(codes.scheme(), "The URI scheme of " + uri + " is not supported");
    }
    try {
      ctx.pc().checkAccess(uri);
    } catch (XProcException e) {
      throw XProcException.xc(codes.access(), e.getMessage());
    }
    return uri;
  }

  private static Path path(URI uri) {
    try {
      // Path.of does not accept a query or fragment
      return Path.of(new URI("file", null, uri.getPath(), null));
    } catch (URISyntaxException | IllegalArgumentException e) {
      throw XProcException.xd(11, "Not a usable file URI: " + uri);
    }
  }

  private static String uriString(Path path) {
    String s = path.toAbsolutePath().normalize().toUri().toString();
    // Path.toUri adds a trailing slash for existing directories only; strip it for consistency
    if (s.endsWith("/") && !Files.isDirectory(path)) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  private static String dirUri(Path path) {
    String s = path.toAbsolutePath().normalize().toUri().toString();
    return s.endsWith("/") ? s : s + "/";
  }

  private static XdmNode cResult(StepContext ctx, String uri) {
    return StepSupport.cResult(ctx.processor(), uri, null);
  }

  private static boolean exists(Path p) {
    return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
  }

  // =========================================================================================
  // p:directory-list

  private static void directoryList(StepContext ctx) {
    URI uri = uri(ctx, "path", new Codes(90, 12));
    boolean detailed = Xdm.bool(ctx.option("detailed"));
    String depthText = Xdm.string(ctx.option("max-depth"));
    int maxDepth;
    if (depthText == null || "unbounded".equals(depthText)) {
      maxDepth = Integer.MAX_VALUE;
    } else {
      try {
        maxDepth = Integer.parseInt(depthText.strip());
      } catch (NumberFormatException e) {
        maxDepth = -1;
      }
      if (maxDepth < 0) {
        throw XProcException.xd(28, "max-depth must be 'unbounded' or a non-negative integer: "
            + depthText);
      }
    }
    Path dir = path(uri);
    if (!Files.isDirectory(dir)) {
      throw XProcException.xc(17, "Not a directory: " + uri);
    }
    if (!Files.isReadable(dir)) {
      throw XProcException.xc(12, "The directory cannot be read: " + uri);
    }
    Filters filters = new Filters(ctx, strings(ctx.option("include-filter")),
        strings(ctx.option("exclude-filter")), overrides(ctx));

    Entry root = list(dir, "", 0, maxDepth, filters);
    String base = dirUri(dir);
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(URI.create(base));
    tw.addStartElement(Ns.c("directory"));
    tw.addAttribute(XML_BASE, base);
    tw.addAttribute(NAME, name(dir));
    if (detailed) {
      details(tw, dir, null);
    }
    for (Entry e : root.children) {
      write(tw, e, detailed, filters);
    }
    tw.addEndElement();
    ctx.output("result", XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.XML,
        URI.create(base)));
  }

  /** A listed file system object; children only for directories. */
  private static final class Entry {
    final Path path;
    final String relative;
    final boolean directory;
    final List<Entry> children = new ArrayList<>();

    Entry(Path path, String relative, boolean directory) {
      this.path = path;
      this.relative = relative;
      this.directory = directory;
    }
  }

  private record Filters(StepContext ctx, List<String> include, List<String> exclude,
      List<String[]> overrides) {

    Filters {
      for (String r : include) {
        matches(ctx, "", r);
      }
      for (String r : exclude) {
        matches(ctx, "", r);
      }
    }

    boolean excluded(String relative) {
      return exclude.stream().anyMatch(r -> matches(ctx, relative, r));
    }

    boolean included(String relative) {
      return include.isEmpty() || include.stream().anyMatch(r -> matches(ctx, relative, r));
    }
  }

  /**
   * Lists the children of a directory. An entry is kept if it matches the include filters
   * (or there are none) or if one of its descendants is kept, and if it does not match the
   * exclude filters.
   */
  private static Entry list(Path dir, String relative, int depth, int maxDepth,
      Filters filters) {
    Entry entry = new Entry(dir, relative, true);
    if (depth >= maxDepth) {
      return entry;
    }
    List<Path> children;
    try (Stream<Path> s = Files.list(dir)) {
      children = s.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
    } catch (IOException e) {
      return entry;
    }
    for (Path child : children) {
      boolean isDir = Files.isDirectory(child);
      String rel = relative + child.getFileName() + (isDir ? "/" : "");
      if (filters.excluded(rel)) {
        continue;
      }
      if (isDir) {
        Entry sub = list(child, rel, depth + 1, maxDepth, filters);
        if (filters.included(rel) || !sub.children.isEmpty()) {
          entry.children.add(sub);
        }
      } else if (filters.included(rel)) {
        entry.children.add(new Entry(child, rel, false));
      }
    }
    return entry;
  }

  private static void write(TreeWriter tw, Entry e, boolean detailed, Filters filters) {
    String name = name(e.path);
    String kind = e.directory ? "directory" : Files.isRegularFile(e.path) ? "file" : "other";
    tw.addStartElement(Ns.c(kind));
    tw.addAttribute(XML_BASE, encode(name) + (e.directory ? "/" : ""));
    tw.addAttribute(NAME, name);
    if (detailed) {
      details(tw, e.path, "file".equals(kind)
          ? contentType(filters.ctx(), e.relative, name, filters.overrides()) : null);
    }
    for (Entry c : e.children) {
      write(tw, c, detailed, filters);
    }
    tw.addEndElement();
  }

  private static String name(Path p) {
    Path n = p.toAbsolutePath().normalize().getFileName();
    return n == null ? "" : n.toString();
  }

  private static String encode(String name) {
    try {
      return new URI(null, null, name, null).getRawPath().replace(":", "%3A");
    } catch (URISyntaxException e) {
      return name;
    }
  }

  /** Writes the attributes of a detailed listing. */
  private static void details(TreeWriter tw, Path p, String contentType) {
    if (contentType != null) {
      tw.addAttribute(new QName("content-type"), contentType);
    }
    tw.addAttribute(new QName("readable"), Boolean.toString(Files.isReadable(p)));
    tw.addAttribute(new QName("writable"), Boolean.toString(Files.isWritable(p)));
    boolean hidden;
    try {
      hidden = Files.isHidden(p);
    } catch (IOException e) {
      hidden = false;
    }
    if (hidden) {
      tw.addAttribute(new QName("hidden"), "true");
    }
    try {
      BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
      tw.addAttribute(new QName("last-modified"), dateTime(a.lastModifiedTime()));
      tw.addAttribute(new QName("size"), Long.toString(a.size()));
    } catch (IOException e) {
      // no further details
    }
  }

  private static String dateTime(FileTime t) {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        t.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).atOffset(ZoneOffset.UTC))
        .replace("+00:00", "Z");
  }

  private static List<String> strings(XdmValue v) {
    List<String> list = new ArrayList<>();
    for (XdmItem i : v) {
      list.add(i.getStringValue());
    }
    return list;
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
      if (m.size() != 1 || !(m.itemAt(0) instanceof XdmArray inner)
          || inner.arrayLength() != 2) {
        throw XProcException.xc(146, "override-content-types must be an array of arrays of "
            + "two strings");
      }
      String regex = inner.get(0).itemAt(0).getStringValue();
      String ct = inner.get(1).itemAt(0).getStringValue();
      MediaType.parse(ct);
      matches(ctx, "", regex);
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

  private static String contentType(StepContext ctx, String matchAgainst, String name,
      List<String[]> overrides) {
    for (String[] o : overrides) {
      if (matches(ctx, matchAgainst, o[0])) {
        return o[1];
      }
    }
    String ct = DocumentLoader.contentTypeForName(name);
    return ct == null ? "application/octet-stream" : ct;
  }

  // =========================================================================================
  // p:file-info

  private static XdmNode info(StepContext ctx) {
    URI uri = uri(ctx, "href", new Codes(134, 135));
    Path p = path(uri);
    if (!exists(p)) {
      throw XProcException.xd(11, "The file does not exist: " + uri);
    }
    boolean dir = Files.isDirectory(p);
    String kind = dir ? "directory" : Files.isRegularFile(p) ? "file" : "other";
    String abs = dir ? dirUri(p) : uriString(p);
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(null);
    tw.addStartElement(Ns.c(kind));
    tw.addAttribute(XML_BASE, abs);
    tw.addAttribute(NAME, name(p));
    details(tw, p, "file".equals(kind)
        ? contentType(ctx, abs, name(p), overrides(ctx)) : null);
    tw.addEndElement();
    return tw.endDocument();
  }

  // =========================================================================================
  // p:file-copy and p:file-move

  private static XdmNode copy(StepContext ctx) {
    Path src = path(uri(ctx, "href", new Codes(144, 145)));
    URI targetUri = uri(ctx, "target", new Codes(144, 145));
    Path target = path(targetUri);
    boolean overwrite = Xdm.bool(ctx.option("overwrite"));
    if (!exists(src)) {
      throw XProcException.xd(11, "The file does not exist: " + src.toUri());
    }
    boolean srcDir = Files.isDirectory(src);
    if (srcDir && exists(target) && !Files.isDirectory(target)) {
      throw XProcException.xc(157, "Cannot copy directory " + src.toUri() + " to file "
          + target.toUri());
    }
    // A directory is copied into the target directory, as is a file into an existing directory
    if (srcDir || Files.isDirectory(target) || targetUri.getPath().endsWith("/")) {
      target = target.resolve(src.getFileName().toString());
    }
    try {
      if (srcDir) {
        copyTree(src, target, overwrite);
      } else {
        if (target.getParent() != null) {
          Files.createDirectories(target.getParent());
        }
        if (overwrite || !exists(target)) {
          Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    } catch (IOException | SecurityException e) {
      throw XProcException.xc(50, "Cannot copy " + src.toUri() + " to " + target.toUri()
          + ": " + describe(e));
    }
    return cResult(ctx, targetUri.toString());
  }

  private static void copyTree(Path src, Path target, boolean overwrite) throws IOException {
    if (exists(target) && !Files.isDirectory(target)) {
      if (!overwrite) {
        return;
      }
      Files.delete(target);
    }
    Files.createDirectories(target);
    List<Path> children;
    try (Stream<Path> s = Files.list(src)) {
      children = s.toList();
    }
    for (Path c : children) {
      Path t = target.resolve(c.getFileName().toString());
      if (Files.isDirectory(c)) {
        copyTree(c, t, overwrite);
      } else if (Files.isDirectory(t)) {
        if (overwrite) {
          deleteTree(t);
          Files.copy(c, t, StandardCopyOption.COPY_ATTRIBUTES);
        }
      } else if (overwrite || !exists(t)) {
        Files.copy(c, t, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES);
      }
    }
  }

  private static XdmNode move(StepContext ctx) {
    Path src = path(uri(ctx, "href", new Codes(148, 149)));
    URI targetUri = uri(ctx, "target", new Codes(148, 149));
    Path target = path(targetUri);
    if (!exists(src)) {
      throw XProcException.xd(11, "The file does not exist: " + src.toUri());
    }
    boolean srcDir = Files.isDirectory(src);
    if (Files.isDirectory(target) || (!srcDir && targetUri.getPath().endsWith("/"))) {
      target = target.resolve(src.getFileName().toString());
    }
    if (exists(target)) {
      if (srcDir && !Files.isDirectory(target)) {
        throw XProcException.xc(158, "Cannot move directory " + src.toUri() + " to file "
            + target.toUri());
      }
      throw XProcException.xc(115, "The target already exists: " + target.toUri());
    }
    try {
      if (target.getParent() != null) {
        Files.createDirectories(target.getParent());
      }
      try {
        Files.move(src, target);
      } catch (IOException e) {
        // for example a directory on another file system
        if (srcDir) {
          copyTree(src, target, true);
          deleteTree(src);
        } else {
          throw e;
        }
      }
    } catch (IOException | SecurityException e) {
      throw XProcException.xc(50, "Cannot move " + src.toUri() + " to " + target.toUri()
          + ": " + describe(e));
    }
    return cResult(ctx, targetUri.toString());
  }

  // =========================================================================================
  // p:file-delete, p:file-mkdir, p:file-create-tempfile, p:file-touch

  private static XdmNode delete(StepContext ctx) {
    URI uri = uri(ctx, "href", new Codes(142, 143));
    Path p = path(uri);
    String result = uri.toString();
    if (!exists(p)) {
      return cResult(ctx, result);
    }
    try {
      if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
        if (Xdm.bool(ctx.option("recursive"))) {
          deleteTree(p);
        } else {
          Files.delete(p);
        }
      } else {
        Files.delete(p);
      }
    } catch (DirectoryNotEmptyException e) {
      throw XProcException.xc(113, "The directory is not empty: " + uri);
    } catch (IOException | SecurityException e) {
      throw XProcException.xd(11, "Cannot delete " + uri + ": " + describe(e));
    }
    return cResult(ctx, result);
  }

  private static XdmNode mkdir(StepContext ctx) {
    URI uri = uri(ctx, "href", new Codes(140, 141));
    Path p = path(uri);
    try {
      Files.createDirectories(p);
    } catch (IOException | SecurityException e) {
      throw XProcException.xc(114, "Cannot create directory " + uri + ": " + describe(e));
    }
    return cResult(ctx, uri.toString());
  }

  private static XdmNode tempfile(StepContext ctx) {
    Path dir;
    if (ctx.option("href").size() == 0) {
      dir = Path.of(System.getProperty("java.io.tmpdir"));
      try {
        ctx.pc().checkAccess(dir.toUri());
      } catch (XProcException e) {
        throw XProcException.xc(139, e.getMessage());
      }
    } else {
      dir = path(uri(ctx, "href", new Codes(138, 139)));
      if (!Files.isDirectory(dir)) {
        throw XProcException.xd(11, "Not an existing directory: " + dir.toUri());
      }
    }
    String prefix = Xdm.string(ctx.option("prefix"));
    String suffix = Xdm.string(ctx.option("suffix"));
    Path file;
    try {
      file = Files.createTempFile(dir, prefix == null ? "" : prefix,
          suffix == null ? "" : suffix);
    } catch (IOException | IllegalArgumentException | SecurityException e) {
      throw XProcException.xc(116, "Cannot create a temporary file in " + dir.toUri() + ": "
          + describe(e));
    }
    if (Xdm.bool(ctx.option("delete-on-exit"))) {
      file.toFile().deleteOnExit();
    }
    return cResult(ctx, uriString(file));
  }

  private static XdmNode touch(StepContext ctx) {
    URI uri = uri(ctx, "href", new Codes(136, 137));
    Path p = path(uri);
    XdmValue ts = ctx.option("timestamp");
    try {
      if (!exists(p)) {
        Files.createFile(p);
      }
      Instant t = ts.size() == 0 ? Instant.now()
          : ((XdmAtomicValue) ts.itemAt(0)).getInstant();
      Files.setLastModifiedTime(p, FileTime.from(t));
    } catch (IOException | SecurityException | UnsupportedOperationException e) {
      throw XProcException.xd(11, "Cannot touch " + uri + ": " + describe(e));
    }
    return cResult(ctx, uri.toString());
  }

  // =========================================================================================

  private static void deleteTree(Path p) throws IOException {
    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
      List<Path> children;
      try (Stream<Path> s = Files.list(p)) {
        children = s.toList();
      }
      for (Path c : children) {
        deleteTree(c);
      }
    }
    Files.delete(p);
  }

  private static String describe(Exception e) {
    if (e instanceof NoSuchFileException) {
      return "no such file " + e.getMessage();
    }
    if (e instanceof AccessDeniedException) {
      return "access denied to " + e.getMessage();
    }
    if (e instanceof FileAlreadyExistsException) {
      return "file exists " + e.getMessage();
    }
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
