package nl.armatiek.xproc.testsuite;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcConfiguration;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.XProcResult;

/**
 * Runs the XProc 3 test suite (https://codeberg.org/XProc/test-suite) against this processor.
 * <p>
 * Usage: {@code TestSuiteRunner TESTS-DIR [REGEX] [REPORT-FILE]}
 */
public final class TestSuiteRunner {

  private static final String T = "http://xproc.org/ns/testsuite/3.0";
  private static final String S = "http://purl.oclc.org/dsdl/schematron";

  /** Steps from the standard step library plus the core language elements. */
  private static final Set<String> KNOWN = Set.of("declare-step", "library", "import",
      "import-functions", "input", "output", "option", "variable", "with-input", "with-option",
      "pipe", "inline", "document", "empty", "documentation", "pipeinfo", "for-each", "viewport",
      "choose", "when", "otherwise", "if", "group", "try", "catch", "finally", "add-attribute",
      "add-xml-base", "archive", "archive-manifest", "cast-content-type", "compare", "compress",
      "count", "delete", "encode", "error", "filter", "hash", "http-request", "identity",
      "insert", "json-join", "json-merge", "label-elements", "load", "make-absolute-uris",
      "message", "namespace-delete", "namespace-rename", "pack", "rename", "replace",
      "set-attributes", "set-properties", "sink", "sleep", "split-sequence", "store",
      "string-replace", "text-count", "text-head", "text-join", "text-replace", "text-sort",
      "text-tail", "unarchive", "uncompress", "unwrap", "uuid", "wrap", "wrap-sequence",
      "www-form-urldecode", "www-form-urlencode", "xinclude", "xquery", "xslt");

  /** Optional features supported by this processor. */
  private static final Set<String> FEATURES = Set.of("archive-order", "HOF", "no-psvi-support",
      "p-count", "p-count-limit", "urify-non-windows", "xquery_1_0", "xquery_3_0", "xquery_3_1",
      "xslt-1", "xslt-1-output-base-uri", "xslt-2", "xslt-3", "xslt-serialization",
      "xslt-function-import", "mac/linux");

  enum Outcome {
    PASS, FAIL, SKIP
  }

  record Result(String name, Outcome outcome, String detail) {
  }

  private final XProcProcessor processor;

  TestSuiteRunner() {
    processor = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT));
  }

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    Pattern filter = args.length > 1 ? Pattern.compile(args[1]) : null;
    TestSuiteRunner runner = new TestSuiteRunner();
    List<Path> files;
    try (var s = Files.list(dir)) {
      files = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
    }
    Map<String, Result> results = new TreeMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(Math.max(2,
        Runtime.getRuntime().availableProcessors()));
    Map<String, Future<Result>> futures = new LinkedHashMap<>();
    for (Path f : files) {
      String name = f.getFileName().toString();
      if (filter != null && !filter.matcher(name).find()) {
        continue;
      }
      futures.put(name, pool.submit(() -> runner.run(f)));
    }
    for (Map.Entry<String, Future<Result>> e : futures.entrySet()) {
      Result r;
      try {
        r = e.getValue().get(60, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException ex) {
        e.getValue().cancel(true);
        r = new Result(e.getKey(), Outcome.FAIL, "timeout");
      } catch (Exception ex) {
        r = new Result(e.getKey(), Outcome.FAIL, "runner error: " + ex);
      }
      results.put(e.getKey(), r);
    }
    pool.shutdownNow();
    int pass = 0;
    int fail = 0;
    int skip = 0;
    PrintStream report = args.length > 2 ? new PrintStream(new File(args[2]), "UTF-8")
        : System.out;
    for (Result r : results.values()) {
      switch (r.outcome()) {
        case PASS -> pass++;
        case FAIL -> fail++;
        case SKIP -> skip++;
      }
      if (r.outcome() != Outcome.PASS) {
        report.println(r.outcome() + " " + r.name() + ": " + r.detail());
      }
    }
    String summary = String.format("Total %d, passed %d, failed %d, skipped (optional "
        + "features) %d", results.size(), pass, fail, skip);
    report.println(summary);
    if (report != System.out) {
      report.close();
      System.out.println(summary);
    }
  }

  Result run(Path file) {
    String name = file.getFileName().toString();
    try {
      DocumentBuilder db = processor.getSaxonProcessor().newDocumentBuilder();
      db.setLineNumbering(true);
      XdmNode doc = db.build(file.toFile());
      XdmNode test = child(doc, new QName(T, "test"));
      String expected = test.getAttributeValue(new QName("expected"));
      XdmNode pipelineEl = child(test, new QName(T, "pipeline"));
      XdmNode pipeline;
      String src = pipelineEl.getAttributeValue(new QName("src"));
      if (src != null) {
        pipeline = db.build(new File(file.getParent().toFile(), src));
      } else {
        pipeline = firstElement(pipelineEl);
      }
      String features = test.getAttributeValue(new QName("features"));
      if (features != null) {
        for (String f : features.trim().split("\\s+")) {
          if (!FEATURES.contains(f)) {
            return new Result(name, Outcome.SKIP, "requires optional feature " + f);
          }
        }
      }
      String optional = optionalFeature(pipeline);
      if (optional != null) {
        return new Result(name, Outcome.SKIP, "uses optional step p:" + optional);
      }
      if (child(test, new QName(T, "file-environment")) != null) {
        return new Result(name, Outcome.SKIP, "requires a file environment");
      }
      String version = pipeline.getAttributeValue(new QName("version"));
      if (version == null && pipeline.getNodeKind() == XdmNodeKind.DOCUMENT) {
        version = firstElement(pipeline).getAttributeValue(new QName("version"));
      }
      if (version != null && !version.equals("3.0") && !version.equals("3.1")) {
        return new Result(name, Outcome.SKIP, "XProc version " + version);
      }
      Map<QName, XdmValue> statics = new LinkedHashMap<>();
      for (XdmNode o : children(test, new QName(T, "option"))) {
        if ("true".equals(o.getAttributeValue(new QName("static")))) {
          XPathCompiler xc = processor.getSaxonProcessor().newXPathCompiler();
          statics.put(optionName(o), xc.evaluate(o.getAttributeValue(new QName("select")),
              null));
        }
      }
      try {
        XProcPipeline p = processor.compile(pipeline, statics);
        Map<String, List<XProcDocument>> inputs = new LinkedHashMap<>();
        for (XdmNode in : children(test, new QName(T, "input"))) {
          String port = in.getAttributeValue(new QName("port"));
          String isrc = in.getAttributeValue(new QName("src"));
          List<XProcDocument> list = inputs.computeIfAbsent(port, k -> new ArrayList<>());
          if (isrc != null) {
            list.add(processor.loadDocument(file.getParent().resolve(isrc).toUri(), null));
          } else {
            for (XdmNode content : in.children()) {
              if (content.getNodeKind() == XdmNodeKind.ELEMENT) {
                list.add(processor.newDocument(content, MediaType.XML, file.toUri()));
              }
            }
          }
        }
        Map<QName, XdmValue> options = new LinkedHashMap<>();
        for (XdmNode o : children(test, new QName(T, "option"))) {
          XPathCompiler xc = processor.getSaxonProcessor().newXPathCompiler();
          for (XdmNode ns : o.axisIterator(Axis.NAMESPACE).stream().toList()) {
            String prefix = ns.getNodeName() == null ? "" : ns.getNodeName().getLocalName();
            if (!prefix.isEmpty()) {
              xc.declareNamespace(prefix, ns.getStringValue());
            }
          }
          xc.setBaseURI(file.toUri());
          if ("true".equals(o.getAttributeValue(new QName("static")))) {
            continue;
          }
          options.put(optionName(o), xc.evaluate(o.getAttributeValue(new QName("select")),
              null));
        }
        XProcResult result = p.run(inputs, options);
        if ("fail".equals(expected)) {
          return new Result(name, Outcome.FAIL, "expected error " + test.getAttributeValue(
              new QName("code")) + " but the pipeline succeeded");
        }
        XdmNode schematron = child(test, new QName(T, "schematron"));
        if (schematron == null) {
          return new Result(name, Outcome.PASS, "");
        }
        List<XProcDocument> docs = result.getDocuments("result");
        if (docs.size() != 1) {
          return new Result(name, Outcome.FAIL, "expected one document on result, got "
              + docs.size());
        }
        XProcDocument checked = docs.get(0);
        if (serializeFirst(schematron)) {
          String ser = processor.serializeToString(checked, p.getSerialization("result"));
          var tw = processor.getSaxonProcessor().newDocumentBuilder().newBuildingStreamWriter();
          tw.writeStartDocument();
          tw.writeStartElement("serialization");
          tw.writeCharacters(ser);
          tw.writeEndElement();
          tw.writeEndDocument();
          checked = processor.newDocument(tw.getDocumentNode(), MediaType.XML, null);
        }
        String failures = checkSchematron(schematron, checked);
        return failures == null ? new Result(name, Outcome.PASS, "")
            : new Result(name, Outcome.FAIL, failures);
      } catch (XProcException e) {
        if ("fail".equals(expected)) {
          String codes = test.getAttributeValue(new QName("code"));
          for (String c : codes.trim().split("\\s+")) {
            String local = c.contains(":") ? c.substring(c.indexOf(':') + 1) : c;
            if (local.equals(e.getCode().getLocalName())) {
              return new Result(name, Outcome.PASS, "");
            }
          }
          return new Result(name, Outcome.FAIL, "expected " + codes + " but got " + e);
        }
        return new Result(name, Outcome.FAIL, "unexpected error " + e);
      }
    } catch (Throwable e) {
      return new Result(name, Outcome.FAIL, "exception " + e);
    }
  }

  private static boolean serializeFirst(XdmNode schematron) {
    for (XdmNode n : schematron.select(net.sf.saxon.s9api.streams.Steps.descendant())
        .toList()) {
      if (n.getNodeKind() == XdmNodeKind.ELEMENT && "rule".equals(n.getNodeName().getLocalName())
          && "/serialization".equals(n.getAttributeValue(new QName("context")))) {
        return true;
      }
    }
    return false;
  }

  private static QName optionName(XdmNode o) {
    String oname = o.getAttributeValue(new QName("name"));
    if (oname.startsWith("Q{")) {
      int close = oname.indexOf('}');
      return new QName(oname.substring(2, close), oname.substring(close + 1));
    }
    return oname.contains(":") ? new QName(nsOf(o, oname.substring(0, oname.indexOf(':'))),
        oname) : new QName(oname);
  }

  private static String nsOf(XdmNode el, String prefix) {
    for (XdmNode ns : el.axisIterator(Axis.NAMESPACE).stream().toList()) {
      if (ns.getNodeName() != null && ns.getNodeName().getLocalName().equals(prefix)) {
        return ns.getStringValue();
      }
    }
    return "";
  }

  private static String optionalFeature(XdmNode node) {
    if (node.getNodeKind() == XdmNodeKind.ELEMENT) {
      QName n = node.getNodeName();
      if (Ns.XPROC.equals(n.getNamespace()) && !KNOWN.contains(n.getLocalName())
          && !isInsideInline(node)) {
        return n.getLocalName();
      }
    }
    for (XdmNode c : node.children()) {
      String r = optionalFeature(c);
      if (r != null) {
        return r;
      }
    }
    return null;
  }

  private static boolean isInsideInline(XdmNode node) {
    for (XdmNode p = node.getParent(); p != null; p = p.getParent()) {
      if (p.getNodeKind() == XdmNodeKind.ELEMENT) {
        QName n = p.getNodeName();
        if (Ns.XPROC.equals(n.getNamespace()) && n.getLocalName().equals("inline")) {
          return true;
        }
        if (!Ns.XPROC.equals(n.getNamespace()) && p.getParent() != null
            && p.getParent().getNodeKind() == XdmNodeKind.ELEMENT
            && Ns.XPROC.equals(p.getParent().getNodeName().getNamespace())
            && List.of("with-input", "input", "output", "variable", "with-option")
                .contains(p.getParent().getNodeName().getLocalName())) {
          return true;
        }
      }
    }
    return false;
  }

  /** A minimal Schematron implementation (patterns, rules, asserts, reports and ns). */
  private String checkSchematron(XdmNode schematron, XProcDocument doc) throws Exception {
    XdmNode schema = firstElement(schematron);
    String schSrc = schematron.getAttributeValue(new QName("src"));
    if (schSrc != null) {
      DocumentBuilder db = processor.getSaxonProcessor().newDocumentBuilder();
      schema = firstElement(db.build(new File(java.net.URI.create(schematron.getBaseURI()
          .toString()).resolve(schSrc))));
    }
    XPathCompiler xc = processor.getSaxonProcessor().newXPathCompiler();
    for (XdmNode ns : schema.axisIterator(Axis.NAMESPACE).stream().toList()) {
      String prefix = ns.getNodeName() == null ? "" : ns.getNodeName().getLocalName();
      if (!prefix.isEmpty()) {
        xc.declareNamespace(prefix, ns.getStringValue());
      }
    }
    for (XdmNode ns : children(schema, new QName(S, "ns"))) {
      xc.declareNamespace(ns.getAttributeValue(new QName("prefix")), ns.getAttributeValue(
          new QName("uri")));
    }
    XdmNode root = doc.getNode();
    if (root == null || doc.getKind() == MediaType.Kind.JSON) {
      return "result is not an XML document (" + doc.getContentType() + ")";
    }
    List<XdmNode> nodes = new ArrayList<>();
    collect(root, nodes);
    List<String> failures = new ArrayList<>();
    for (XdmNode pattern : children(schema, new QName(S, "pattern"))) {
      List<XdmNode> rules = children(pattern, new QName(S, "rule"));
      List<XPathExecutable> contexts = new ArrayList<>();
      for (XdmNode rule : rules) {
        contexts.add(xc.compilePattern(rule.getAttributeValue(new QName("context"))));
      }
      for (XdmNode node : nodes) {
        for (int i = 0; i < rules.size(); i++) {
          XPathSelector sel = contexts.get(i).load();
          sel.setContextItem(node);
          if (!sel.effectiveBooleanValue()) {
            continue;
          }
          for (XdmNode a : rules.get(i).children()) {
            if (a.getNodeKind() != XdmNodeKind.ELEMENT || !S.equals(a.getNodeName()
                .getNamespace())) {
              continue;
            }
            boolean isAssert = a.getNodeName().getLocalName().equals("assert");
            boolean isReport = a.getNodeName().getLocalName().equals("report");
            if (!isAssert && !isReport) {
              continue;
            }
            XPathSelector ts = xc.compile(a.getAttributeValue(new QName("test"))).load();
            ts.setContextItem(node);
            boolean v = ts.effectiveBooleanValue();
            if ((isAssert && !v) || (isReport && v)) {
              failures.add(a.getStringValue().trim());
            }
          }
          break;
        }
      }
    }
    if (failures.isEmpty()) {
      return null;
    }
    String out = processor.serializeToString(doc, Map.of());
    if (out.length() > 400) {
      out = out.substring(0, 400) + "...";
    }
    return "schematron: " + String.join(" | ", failures) + " -- output: " + out.replace('\n',
        ' ');
  }

  private static void collect(XdmNode n, List<XdmNode> out) {
    out.add(n);
    if (n.getNodeKind() == XdmNodeKind.ELEMENT) {
      out.addAll(n.axisIterator(Axis.ATTRIBUTE).stream().toList());
    }
    for (XdmNode c : n.children()) {
      collect(c, out);
    }
  }

  private static XdmNode child(XdmNode n, QName name) {
    for (XdmNode c : n.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT && c.getNodeName().equals(name)) {
        return c;
      }
    }
    return null;
  }

  private static List<XdmNode> children(XdmNode n, QName name) {
    List<XdmNode> list = new ArrayList<>();
    for (XdmNode c : n.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT && c.getNodeName().equals(name)) {
        list.add(c);
      }
    }
    return list;
  }

  private static XdmNode firstElement(XdmNode n) {
    for (XdmNode c : n.children()) {
      if (c.getNodeKind() == XdmNodeKind.ELEMENT) {
        return c;
      }
    }
    return null;
  }
}
