# Armatiek XProc – an XProc 3.1 processor for Java

An implementation of [XProc 3.1: An XML Pipeline Language](https://spec.xproc.org/3.1/xproc/)
and the required steps of the
[XProc 3.1 Standard Step Library](https://spec.xproc.org/3.1/steps/), built on
[Saxon-HE](https://www.saxonica.com/) 13.1 and Java 25.

The project consists of two Maven modules:

| Module       | Contents                                                                 |
|--------------|--------------------------------------------------------------------------|
| `xproc-core` | The processor as a library with a small public API (`nl.armatiek.xproc`) |
| `xproc-cli`  | A command line interface, packaged as an executable "fat" jar            |

## Building

Maven does not need to be installed; the Maven wrapper is included:

```bash
./mvnw clean install
```

This produces `xproc-core/target/xproc-core-1.0.0-SNAPSHOT.jar` and the executable
`xproc-cli/target/xproc-cli-1.0.0-SNAPSHOT-all.jar` (which includes all dependencies).

## Command line usage

```bash
java -jar xproc-cli/target/xproc-cli-1.0.0-SNAPSHOT-all.jar -i source=in.xml -o result=out.xml pipeline.xpl param=value
```

```
Usage: xproc [options] PIPELINE [NAME=VALUE ...]

  -i, --input PORT=DOC        Connect a document (file, URI or "-" for stdin) to an input port.
                              Repeat to supply a sequence of documents.
  -t, --input-type PORT=TYPE  Content type for documents bound to PORT.
  -e, --empty PORT            Connect an input port to an empty sequence.
  -o, --output PORT=FILE      Write an output port to FILE ("-" for stdout). Without this
                              option the primary output port is written to stdout.
  -p, --option NAME=VALUE     Set a pipeline option to a string (same as NAME=VALUE).
  -x, --xpath NAME=EXPR       Set a pipeline option to the result of an XPath expression.
  -s, --static NAME=VALUE     Set the value of a static option.
  -n, --namespace PREFIX=URI  Bind a prefix for option names and XPath expressions.
      --serialize NAME=VALUE  Serialization parameter for stdout (e.g. indent=true).
      --safe                  Forbid file and network access.
  -q, --quiet                 Suppress messages.
  -v, --verbose               Report timings and stack traces.
```

Exit codes: `0` success, `1` pipeline error (static or dynamic, the error code is printed),
`2` invalid command line.

## Library usage

```java
XProcProcessor processor = new XProcProcessor(new XProcConfiguration()
    .setMessageListener(MessageListener.STDERR));

XProcPipeline pipeline = processor.compile(new File("pipeline.xpl"));

XProcResult result = pipeline.newInvocation()
    .withInput("source", processor.loadDocument(new File("in.xml"), null))
    .withOption("param", "value")                       // string (untypedAtomic) value
    .withOption(new QName("count"), new XdmAtomicValue(3))
    .run();

for (XProcDocument doc : result.getPrimaryDocuments()) {
  processor.serialize(doc, System.out, pipeline.getSerialization(result.getPrimaryPort()));
}
```

The main types:

* `XProcProcessor` – compiles pipelines (from a `File`, `URI`, `Source`, string or `XdmNode`),
  loads, parses and serializes documents. Thread-safe.
* `XProcConfiguration` – message listener, URI mapper (also used by `p:lookup-uri()`), default
  values for static options, file/network access, locale, and an optional existing Saxon
  `Processor`.
* `XProcPipeline` – a compiled pipeline; exposes its signature (`getInputs()`, `getOutputs()`,
  `getOptions()`) and the serialization parameters of its output ports. Immutable and safe to
  run concurrently.
* `XProcDocument` – a document (XML, HTML, text, JSON or binary) plus its document properties.
* `XProcResult` – the documents on each output port.
* `XProcException` – every error carries its XProc (or XPath) error code as a `QName`.

## Conformance

All features of the core language are implemented, together with all 51 required steps of
the standard step library. Optional features (such as the `p:file-*`, `p:os-*`, `p:run` and
validation steps, PSVI support, and XQuery function imports) are not implemented.

The processor has been tested against the
[XProc test suite](https://codeberg.org/XProc/test-suite). Of the 2453 tests that apply to
this processor, 2421 pass (98.7%). The suite's other 910 tests were skipped because they need
optional features, a Windows host (`p:urify` Windows behaviour), XProc 3.2, or the suite's
Docker-based HTTP test server. `p:http-request` is covered by this project's own tests against
an in-process HTTP server instead.

Most of the 32 failing tests are cases where a test expects a different error code from the
one the specification names. In those cases this implementation follows the specification:

* `err:XS0094` (not `XS0038`) for a `p:variable` without `select`.
* `err:XS0088` (not `XS0091`) for a variable that shadows a static option.
* `err:XS0027` (not `XS0080`) for an option given both as an attribute and with
  `p:with-option`.
* `err:XD0065` (not `XD0001`) for a context sequence in `p:with-option`/value templates.

A few other edge cases remain open: some `p:cast-content-type` and override-content-type error
codes, `p:import-functions` error codes, and a few base URI and `use-when` corner cases.

To run the test suite yourself:

```bash
git clone https://codeberg.org/XProc/test-suite.git
./mvnw -pl xproc-core test -Dtest=TestSuiteTest -Dxproc.testsuite=$PWD/test-suite/test-suite/tests
```

The report is written to `xproc-core/target/testsuite-report.txt`.

## Implementation-defined features

The XProc specifications require implementations to document their implementation-defined
behaviour. The most relevant choices are:

* **Versions**: pipelines labelled `3.0` are accepted and processed as `3.1`; other versions
  raise `err:XS0060`. XPath 3.1 is used for all expressions (`xpath-version` must be `3.1`).
* **System properties**: `p:product-name` = `Armatiek XProc`, `p:vendor` = `Armatiek`,
  `p:vendor-uri` = `https://www.armatiek.nl`, `p:version` = `3.1`, `p:xpath-version` = `3.1`,
  `p:psvi-supported` = `false`, `p:locale` = the JVM default locale (configurable). No other
  system properties are provided.
* **Static context**: only the in-scope namespaces of an element are statically known; Saxon's
  predeclared prefixes (`xs`, `map`, `array`, …) are not available unless declared. The
  codepoint and Saxon's UCA collations are available.
* **Inputs and outputs of the top-level pipeline**: connected through the API or CLI. An input
  port without documents and without a default connection receives an empty sequence.
* **URI schemes**: `file:`, `http:`, `https:`, `jar:`, `classpath:` and `data:` can be read;
  only `file:` can be written by `p:store`. A configurable URI mapper acts as URI resolver.
* **Content types**: in addition to the media types named in the specification,
  `application/x-www-form-urlencoded`, `application/ecmascript`, `application/x-javascript`
  and `application/sparql-query` are text media types. Without other information the content
  type of a resource is derived from its file extension, falling back to
  `application/octet-stream`. No additional content type shortcuts are supported.
* **HTML** is parsed with the validator.nu HTML5 parser.
* **Serialization**: Saxon's serializer, with method defaults derived from the content type.
  Binary documents are written as-is.
* **Expressions**: options and variables are evaluated eagerly. Type errors detected while
  compiling an expression are raised when the expression is evaluated.
* **Timeouts**: `[p:]timeout` values are validated but not enforced.
* **Messages**: `[p:]message`, `p:message`, `xsl:message` and `fn:message` output goes to the
  configured `MessageListener` (standard error by default).
* **`p:import-functions`**: XSLT packages (`application/xslt+xml`) only.
* **Steps**:
  * `p:xslt` runs XSLT 1.0 and 2.0 stylesheets with the XSLT 3.0 processor, applying the
    version specific invocation rules; static parameters are supported.
  * `p:xquery` supports XQuery 3.1 (and 1.0/3.0 queries), not XQueryX.
  * `p:hash`: `crc` (32), `md` (5), `sha` (1, 224, 256, 384, 512).
  * `p:uuid`: version 4.
  * `p:archive`, `p:archive-manifest`, `p:unarchive`: ZIP only.
  * `p:compress`, `p:uncompress`: GZIP only.
  * `p:compare`: the `deep-equal` method only.
  * `p:http-request`: methods GET, POST, PUT, DELETE, HEAD, PATCH, OPTIONS and TRACE; Basic
    and Digest authentication; redirects are followed by default.

## Project layout

```
xproc-core/src/main/java/nl/armatiek/xproc/          public API
xproc-core/src/main/java/nl/armatiek/xproc/internal/
    parser/    static analysis: imports, use-when, scoping, connections, static errors
    model/     the compiled pipeline model
    runtime/   the evaluator (frames, compound steps, inline documents, errors)
    xpath/     XPath evaluation, type conversion and the p: extension functions
    io/        document loading (XML, HTML, text, JSON, binary) and serialization
    steps/     implementations of the standard steps
xproc-core/src/main/resources/nl/armatiek/xproc/steps.xpl   step declarations
xproc-cli/src/main/java/nl/armatiek/xproc/cli/Main.java     command line interface
```

`steps.xpl` contains the step declarations published as an ancillary file of the XProc 3.1
Standard Step Library specification (W3C Community Group).
