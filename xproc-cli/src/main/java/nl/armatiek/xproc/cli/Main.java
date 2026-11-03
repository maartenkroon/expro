package nl.armatiek.xproc.cli;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.PortSignature;
import nl.armatiek.xproc.XProcConfiguration;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.XProcResult;

/**
 * Command line interface for the XProc 3.1 processor.
 */
public final class Main {

  /** Exit code for successful runs. */
  public static final int OK = 0;
  /** Exit code for pipeline errors (static or dynamic). */
  public static final int PIPELINE_ERROR = 1;
  /** Exit code for invalid command lines. */
  public static final int USAGE_ERROR = 2;

  private final PrintStream out;
  private final PrintStream err;

  private record Binding(String port, String value, String contentType) {
  }

  private record OptionArg(String name, String value, boolean xpath) {
  }

  Main(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    int rc = new Main(System.out, System.err).run(args);
    System.out.flush();
    System.exit(rc);
  }

  private static final String USAGE = """
      Usage: xproc [options] PIPELINE [NAME=VALUE ...]

      Runs the XProc 3.1 pipeline PIPELINE (a file name or URI).

      Options:
        -i, --input PORT=DOC        Connect a document to an input port. DOC is a file name,
                                    a URI, or "-" for standard input. May be repeated to
                                    supply a sequence of documents.
        -t, --input-type PORT=TYPE  Content type to use for documents bound to PORT
                                    (default: derived from the file name / protocol).
        -e, --empty PORT            Connect an input port to an empty sequence.
        -o, --output PORT=FILE      Write the documents on an output port to FILE ("-" for
                                    standard output). Without this option the primary output
                                    port is written to standard output.
        -p, --option NAME=VALUE     Set a pipeline option to a string value (same as a
                                    trailing NAME=VALUE argument).
        -x, --xpath NAME=EXPR       Set a pipeline option to the result of an XPath
                                    expression.
        -s, --static NAME=VALUE     Set the value of a static option.
        -n, --namespace PREFIX=URI  Bind a namespace prefix for option names and XPath
                                    expressions.
            --serialize NAME=VALUE  Set a serialization parameter for standard output
                                    (for example indent=true).
            --safe                  Forbid file and network access to resources and
                                    running commands (p:os-exec).
        -q, --quiet                 Suppress messages ([p:]message, p:message, xsl:message).
        -v, --verbose               Report run times and stack traces on errors.
        -h, --help                  Show this help.
            --version               Show version information.

      Option names may be prefixed QNames (bound with -n) or EQNames (Q{uri}local).
      """;

  int run(String[] args) {
    List<Binding> inputs = new ArrayList<>();
    Map<String, String> inputTypes = new LinkedHashMap<>();
    List<String> emptyInputs = new ArrayList<>();
    Map<String, String> outputs = new LinkedHashMap<>();
    List<OptionArg> options = new ArrayList<>();
    List<OptionArg> statics = new ArrayList<>();
    Map<String, String> namespaces = new LinkedHashMap<>();
    Map<String, String> serialization = new LinkedHashMap<>();
    String pipeline = null;
    boolean quiet = false;
    boolean verbose = false;
    boolean safe = false;
    try {
      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        switch (a) {
          case "-h", "--help" -> {
            out.print(USAGE);
            return OK;
          }
          case "--version" -> {
            XProcProcessor p = new XProcProcessor();
            out.println(p.getProductName() + " " + p.getProductVersion() + " (XProc 3.1, "
                + p.getSaxonProcessor().getSaxonProductVersion() + " "
                + p.getSaxonProcessor().getSaxonEdition() + ")");
            return OK;
          }
          case "-i", "--input" -> {
            String[] kv = pair(a, value(args, ++i, a));
            inputs.add(new Binding(kv[0], kv[1], null));
          }
          case "-t", "--input-type" -> {
            String[] kv = pair(a, value(args, ++i, a));
            inputTypes.put(kv[0], kv[1]);
          }
          case "-e", "--empty" -> emptyInputs.add(value(args, ++i, a));
          case "-o", "--output" -> {
            String[] kv = pair(a, value(args, ++i, a));
            outputs.put(kv[0], kv[1]);
          }
          case "-p", "--option" -> {
            String[] kv = pair(a, value(args, ++i, a));
            options.add(new OptionArg(kv[0], kv[1], false));
          }
          case "-x", "--xpath" -> {
            String[] kv = pair(a, value(args, ++i, a));
            options.add(new OptionArg(kv[0], kv[1], true));
          }
          case "-s", "--static" -> {
            String[] kv = pair(a, value(args, ++i, a));
            statics.add(new OptionArg(kv[0], kv[1], false));
          }
          case "-n", "--namespace" -> {
            String[] kv = pair(a, value(args, ++i, a));
            namespaces.put(kv[0], kv[1]);
          }
          case "--serialize" -> {
            String[] kv = pair(a, value(args, ++i, a));
            serialization.put(kv[0], kv[1]);
          }
          case "--safe" -> safe = true;
          case "-q", "--quiet" -> quiet = true;
          case "-v", "--verbose" -> verbose = true;
          default -> {
            if (a.startsWith("-") && a.length() > 1) {
              throw new UsageException("Unknown option: " + a);
            }
            if (pipeline == null) {
              pipeline = a;
            } else {
              String[] kv = pair("option", a);
              options.add(new OptionArg(kv[0], kv[1], false));
            }
          }
        }
      }
      if (pipeline == null) {
        throw new UsageException("No pipeline specified");
      }
    } catch (UsageException e) {
      err.println("xproc: " + e.getMessage());
      err.println("Try 'xproc --help' for more information.");
      return USAGE_ERROR;
    }

    long start = System.nanoTime();
    XProcConfiguration config = new XProcConfiguration()
        .setMessageListener(quiet ? MessageListener.SILENT : MessageListener.STDERR)
        .setAllowFileAccess(!safe)
        .setAllowNetworkAccess(!safe)
        .setAllowOsAccess(!safe);
    try {
      XProcProcessor processor = new XProcProcessor(config);
      Map<QName, XdmValue> staticValues = new LinkedHashMap<>();
      for (OptionArg o : statics) {
        staticValues.put(qname(o.name(), namespaces), untyped(o.value()));
      }
      URI pipelineUri = toUri(pipeline);
      XProcPipeline p = processor.compile(pipelineUri, staticValues);
      if (verbose) {
        err.printf("Compiled %s in %d ms%n", pipelineUri, (System.nanoTime() - start) / 1000000);
      }

      Map<String, List<XProcDocument>> docs = new LinkedHashMap<>();
      for (String port : emptyInputs) {
        docs.put(port, new ArrayList<>());
      }
      for (Binding b : inputs) {
        String ct = inputTypes.get(b.port());
        XProcDocument d;
        if ("-".equals(b.value())) {
          byte[] bytes = System.in.readAllBytes();
          MediaType mt = MediaType.parse(ct == null ? "application/xml" : ct);
          d = processor.parseDocument(bytes, mt, Path.of("").toAbsolutePath().toUri());
        } else {
          d = processor.loadDocument(toUri(b.value()), ct);
        }
        docs.computeIfAbsent(b.port(), k -> new ArrayList<>()).add(d);
      }

      Map<QName, XdmValue> optionValues = new LinkedHashMap<>();
      for (OptionArg o : options) {
        QName name = qname(o.name(), namespaces);
        if (o.xpath()) {
          XPathCompiler xc = processor.getSaxonProcessor().newXPathCompiler();
          for (Map.Entry<String, String> e : namespaces.entrySet()) {
            xc.declareNamespace(e.getKey(), e.getValue());
          }
          optionValues.put(name, xc.evaluate(o.value(), null));
        } else {
          optionValues.put(name, untyped(o.value()));
        }
      }

      long runStart = System.nanoTime();
      XProcResult result = p.run(docs, optionValues);
      if (verbose) {
        err.printf("Ran pipeline in %d ms%n", (System.nanoTime() - runStart) / 1000000);
      }

      if (outputs.isEmpty() && p.getPrimaryOutput() != null) {
        outputs.put(p.getPrimaryOutput(), "-");
      }
      for (Map.Entry<String, String> e : outputs.entrySet()) {
        boolean known = false;
        for (PortSignature ps : p.getOutputs()) {
          known |= ps.name().equals(e.getKey());
        }
        if (!known) {
          err.println("xproc: the pipeline has no output port named '" + e.getKey() + "'");
          return USAGE_ERROR;
        }
        writeOutput(processor, p, result, e.getKey(), e.getValue(), serialization, namespaces);
      }
      return OK;
    } catch (XProcException e) {
      err.println("xproc: " + e);
      if (verbose) {
        e.printStackTrace(err);
      }
      return PIPELINE_ERROR;
    } catch (UsageException e) {
      err.println("xproc: " + e.getMessage());
      return USAGE_ERROR;
    } catch (SaxonApiException e) {
      err.println("xproc: invalid XPath expression: " + e.getMessage());
      return USAGE_ERROR;
    } catch (IOException e) {
      err.println("xproc: " + e.getMessage());
      return PIPELINE_ERROR;
    }
  }

  private void writeOutput(XProcProcessor processor, XProcPipeline p, XProcResult result,
      String port, String target, Map<String, String> serialization,
      Map<String, String> namespaces) throws IOException {
    Map<QName, XdmValue> params = new LinkedHashMap<>(p.getSerialization(port));
    for (Map.Entry<String, String> e : serialization.entrySet()) {
      params.put(qname(e.getKey(), namespaces), untyped(e.getValue()));
    }
    List<XProcDocument> docs = result.getDocuments(port);
    if ("-".equals(target)) {
      boolean first = true;
      for (XProcDocument d : docs) {
        if (!first) {
          out.println();
        }
        processor.serialize(d, out, params);
        first = false;
      }
      if (!docs.isEmpty()) {
        out.println();
      }
      out.flush();
      return;
    }
    Path path = Path.of(target);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(path.toFile()))) {
      boolean first = true;
      for (XProcDocument d : docs) {
        if (!first) {
          os.write('\n');
        }
        processor.serialize(d, os, params);
        first = false;
      }
    }
  }

  private static URI toUri(String s) {
    if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]+:.*")) {
      try {
        URI u = new URI(s);
        if (u.isAbsolute()) {
          return u;
        }
      } catch (Exception e) {
        // treat as a file name
      }
    }
    return new File(s).getAbsoluteFile().toURI();
  }

  private static QName qname(String name, Map<String, String> namespaces) {
    if (name.startsWith("Q{")) {
      int close = name.indexOf('}');
      return new QName(name.substring(2, close), name.substring(close + 1));
    }
    int colon = name.indexOf(':');
    if (colon > 0) {
      String prefix = name.substring(0, colon);
      String uri = namespaces.get(prefix);
      if (uri == null) {
        throw new UsageException("Unbound namespace prefix '" + prefix
            + "' (use -n " + prefix + "=URI)");
      }
      return new QName(prefix, uri, name.substring(colon + 1));
    }
    return new QName(name);
  }

  private static XdmValue untyped(String value) {
    return XdmValue.wrap(net.sf.saxon.value.StringValue.makeUntypedAtomic(
        net.sf.saxon.str.StringView.of(value)));
  }

  private static String value(String[] args, int i, String option) {
    if (i >= args.length) {
      throw new UsageException("Option " + option + " requires a value");
    }
    return args[i];
  }

  private static String[] pair(String option, String s) {
    int eq = s.indexOf('=');
    if (eq <= 0) {
      throw new UsageException("Expected NAME=VALUE for " + option + ", got '" + s + "'");
    }
    return new String[] {s.substring(0, eq), s.substring(eq + 1)};
  }

  /** An invalid command line. */
  static final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UsageException(String msg) {
      super(msg);
    }
  }
}
