package nl.armatiek.xproc.internal.steps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.util.TreeWriter;
import nl.armatiek.xproc.internal.util.Xdm;
import nl.armatiek.xproc.internal.xpath.Urify;

/** The steps of the XProc 3.1 Operating System Steps specification. */
final class OsSteps {

  private OsSteps() {
  }

  static void register(StepRegistry r) {
    r.register("os-info", () -> OsSteps::info);
    r.register("os-exec", () -> OsSteps::exec);
  }

  /**
   * p:os-info. When the configuration forbids access to the operating system, the working
   * directory and user details are empty and no environment variables are reported.
   */
  private static void info(StepContext ctx) {
    boolean os = ctx.pc().config.isAllowOsAccess();
    TreeWriter tw = new TreeWriter(ctx.processor());
    tw.startDocument(null);
    tw.addStartElement(Ns.c("result"));
    tw.addAttribute(new QName("file-separator"), java.io.File.separator);
    tw.addAttribute(new QName("path-separator"), java.io.File.pathSeparator);
    tw.addAttribute(new QName("os-architecture"), System.getProperty("os.arch", ""));
    tw.addAttribute(new QName("os-name"), System.getProperty("os.name", ""));
    tw.addAttribute(new QName("os-version"), System.getProperty("os.version", ""));
    tw.addAttribute(new QName("cwd"), os ? System.getProperty("user.dir", "") : "");
    tw.addAttribute(new QName("user-name"), os ? System.getProperty("user.name", "") : "");
    tw.addAttribute(new QName("user-home"), os ? System.getProperty("user.home", "") : "");
    Map<String, String> env = os ? new TreeMap<>(System.getenv()) : Map.of();
    for (Map.Entry<String, String> e : env.entrySet()) {
      tw.addStartElement(Ns.c("environment"));
      tw.addAttribute(new QName("name"), e.getKey());
      tw.addAttribute(new QName("value"), e.getValue());
      tw.addEndElement();
    }
    tw.addEndElement();
    ctx.output("result", XProcDocument.of(ctx.processor(), tw.endDocument(), MediaType.XML,
        (URI) null));
  }

  private static void exec(StepContext ctx) {
    if (!ctx.pc().config.isAllowOsAccess()) {
      throw XProcException.xc(33, "Running commands is not allowed by the configuration");
    }
    List<XProcDocument> source = ctx.inputs("source");
    if (source.size() > 1) {
      throw XProcException.xc(32, "p:os-exec accepts at most one document on its source port");
    }
    String separator = Xdm.string(ctx.option("path-separator"));
    if (separator != null && separator.length() != 1) {
      throw XProcException.xc(63, "path-separator must be a single character: '" + separator
          + "'");
    }
    List<String> command = new ArrayList<>();
    command.add(separators(Xdm.string(ctx.option("command")), separator));
    for (XdmItem a : ctx.option("args")) {
      command.add(separators(a.getStringValue(), separator));
    }
    MediaType resultType = MediaType.parse(Xdm.string(ctx.option("result-content-type")));
    MediaType errorType = MediaType.parse(Xdm.string(ctx.option("error-content-type")));
    XdmValue thresholdValue = ctx.option("failure-threshold");

    ProcessBuilder pb = new ProcessBuilder(command);
    String cwd = Xdm.string(ctx.option("cwd"));
    if (cwd != null) {
      Path dir = cwdPath(ctx, separators(cwd, separator));
      if (dir == null || !Files.isDirectory(dir)) {
        throw XProcException.xc(34, "Cannot change the working directory to " + cwd);
      }
      pb.directory(dir.toFile());
    }

    byte[] stdin = null;
    if (!source.isEmpty()) {
      Map<QName, XdmValue> params = DocumentSerializer.toParams(ctx.option("serialization"));
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ctx.pc().serializer.serialize(source.get(0), bos, params);
      stdin = bos.toByteArray();
    }

    Process process;
    try {
      process = pb.start();
    } catch (IOException | SecurityException e) {
      throw XProcException.xc(33, "Cannot run " + command.get(0) + ": " + e.getMessage());
    }
    CompletableFuture<byte[]> out = read(process.getInputStream());
    CompletableFuture<byte[]> err = read(process.getErrorStream());
    int exit;
    try (OutputStream in = process.getOutputStream()) {
      if (stdin != null) {
        in.write(stdin);
      }
    } catch (IOException e) {
      // the command does not read its standard input
    }
    byte[] outBytes;
    byte[] errBytes;
    try {
      exit = process.waitFor();
      outBytes = out.join();
      errBytes = err.join();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw XProcException.xc(33, "Interrupted while running " + command.get(0));
    }

    if (thresholdValue.size() > 0 && exit > Xdm.integer(thresholdValue, 0)) {
      throw XProcException.xc(64, "The exit status " + exit + " of " + command.get(0)
          + " exceeds the failure threshold " + Xdm.integer(thresholdValue, 0));
    }
    if (outBytes.length > 0) {
      ctx.output("result", parse(ctx, outBytes, resultType));
    }
    if (errBytes.length > 0) {
      ctx.output("error", parse(ctx, errBytes, errorType));
    }
    ctx.output("exit-status", XProcDocument.of(ctx.processor(), StepSupport.cResult(
        ctx.processor(), Integer.toString(exit), null), MediaType.XML, (URI) null));
  }

  private static String separators(String value, String separator) {
    if (separator == null || value == null) {
      return value;
    }
    return value.replace(separator, java.io.File.separator);
  }

  /** The cwd option is a path or a URI; it is normalized as by p:urify(). */
  private static Path cwdPath(StepContext ctx, String cwd) {
    try {
      String base = ctx.baseUri() == null ? null : ctx.baseUri().toString();
      URI uri = URI.create(new Urify().urify(cwd, base));
      if (!"file".equalsIgnoreCase(uri.getScheme())) {
        return null;
      }
      ctx.pc().checkAccess(uri);
      return Path.of(uri);
    } catch (IllegalArgumentException | XProcException e) {
      return null;
    }
  }

  private static CompletableFuture<byte[]> read(InputStream in) {
    return CompletableFuture.supplyAsync(() -> {
      try (in) {
        return in.readAllBytes();
      } catch (IOException e) {
        return new byte[0];
      }
    }, Thread::startVirtualThread);
  }

  private static XProcDocument parse(StepContext ctx, byte[] bytes, MediaType ct) {
    return ctx.pc().loader.parse(bytes, ct, ct.getCharset() == null ? null
        : java.nio.charset.Charset.forName(ct.getCharset()), null, null, Map.of(), true);
  }
}
