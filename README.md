# Expro – an XProc 3.1 processor for Java

An implementation of [XProc 3.1: An XML Pipeline Language](https://spec.xproc.org/3.1/xproc/)
and the required steps of the
[XProc 3.1 Standard Step Library](https://spec.xproc.org/3.1/steps/), built on
[Saxon-HE](https://www.saxonica.com/) 13.1 and Java 25. It also implements the steps of the
optional [file](https://spec.xproc.org/3.1/file/),
[operating system](https://spec.xproc.org/3.1/os/),
[`p:run`](https://spec.xproc.org/3.1/run/),
[validation](https://spec.xproc.org/3.1/validation/),
[mail](https://spec.xproc.org/3.1/mail/), [text](https://spec.xproc.org/3.1/text/) and
[Invisible XML](https://spec.xproc.org/3.1/ixml/) step libraries.

The project consists of three Maven modules:

| Module       | Contents                                                                 |
|--------------|--------------------------------------------------------------------------|
| `xproc-core` | The processor as a library with a small public API (`nl.armatiek.xproc`) |
| `xproc-cli`  | A command line interface, packaged as an executable "fat" jar            |
| `xproc-rest` | A Spring Boot web application with an OpenAPI-defined REST interface     |
| `xproc-openapi` | A Spring Boot web service whose OpenAPI operations are XProc pipelines |

## Building

Maven does not need to be installed; the Maven wrapper is included:

```bash
./mvnw clean install
```

This produces `xproc-core/target/xproc-core-1.0.0-SNAPSHOT.jar` and the executable
`xproc-cli/target/xproc-cli-1.0.0-SNAPSHOT-all.jar` (which includes all dependencies) and the
executable Spring Boot jars `xproc-rest/target/xproc-rest-1.0.0-SNAPSHOT.jar` and
`xproc-openapi/target/xproc-openapi-1.0.0-SNAPSHOT.jar`.

The Saxon edition is chosen with a Maven profile. `Saxon-HE` (Saxon-HE from Maven Central) is
the default; `Saxon-PE` and `Saxon-EE` use Saxon-PE or Saxon-EE from the
[Saxonica repository](https://maven.saxonica.com/maven), which the build already lists:

```bash
./mvnw clean install -P Saxon-EE
```

The processor creates its Saxon `Processor` from `Configuration.newLicensedConfiguration()`,
which selects the most capable edition that is both on the classpath and licensed (EE, then
PE, otherwise HE). Without a Saxonica license, PE and EE therefore run with HE features. A
`Processor` passed to `XProcConfiguration.setSaxonProcessor()` is used as it is.

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
      --safe                  Forbid file and network access and running commands.
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
  loads, parses and serializes documents. Thread-safe. It keeps compiled XSLT stylesheets (of
  `p:xslt` and of Schematron schemas) for reuse; `clearCaches()` discards them.
* `XProcConfiguration` – message listener, URI mapper (also used by `p:lookup-uri()`), default
  values for static options, file/network access, locale, the size of the XSLT cache
  (`setXsltCacheSize`, default 100, 0 disables it), and an optional existing Saxon
  `Processor`.
* `XProcPipeline` – a compiled pipeline; exposes its signature (`getInputs()`, `getOutputs()`,
  `getOptions()`) and the serialization parameters of its output ports. Immutable and safe to
  run concurrently.
* `XProcDocument` – a document (XML, HTML, text, JSON or binary) plus its document properties.
* `XProcResult` – the documents on each output port.
* `XProcException` – every error carries its XProc (or XPath) error code as a `QName`.
  `isCallerError()` is true when the cause is what the caller supplied (an option value of the
  wrong type, a missing required option), not the pipeline.

A compiled stylesheet is identified by a digest of the stylesheet document, its base URI and
its static parameters, so a changed stylesheet is compiled again. Modules it includes or imports
are not part of the digest: call `clearCaches()` after changing them.

## REST service

The `xproc-rest` module is a Spring Boot 4 application that exposes the library through a REST
interface. The interface is developed schema first: the OpenAPI 3 specification
[`openapi.yaml`](xproc-rest/src/main/resources/static/openapi.yaml) is the source of truth.
At build time, OpenAPI Generator turns it into the Java API interfaces and models
(`nl.armatiek.xproc.rest.api`, `nl.armatiek.xproc.rest.model`), and the controllers implement
those interfaces.

```bash
java -jar xproc-rest/target/xproc-rest-1.0.0-SNAPSHOT.jar
```

The service listens on port 8080. Swagger UI for the specification is served at `/`, and the
specification itself at `/openapi.yaml`. All API operations are under `/api/v1`:

| Operation                                     | Purpose                                              |
|-----------------------------------------------|------------------------------------------------------|
| `GET /processor`                              | Processor and Saxon version, access settings         |
| `GET /pipelines`                              | List the stored pipelines                            |
| `PUT /pipelines/{id}`                         | Compile a pipeline (XML request body) and store it   |
| `GET /pipelines/{id}`                         | The signature of a pipeline (ports and options)      |
| `GET /pipelines/{id}/source`                  | The pipeline document as uploaded                    |
| `DELETE /pipelines/{id}`                      | Remove a pipeline                                    |
| `POST /pipelines/{id}/runs`                   | Run a pipeline: JSON documents in, JSON documents out |
| `POST /pipelines/{id}/transform`              | Run a pipeline: raw document in, raw document out    |
| `POST /runs`                                  | Compile and run a pipeline in one request            |

For example:

```bash
curl -X PUT -H 'Content-Type: application/xml' --data-binary @pipeline.xpl \
     http://localhost:8080/api/v1/pipelines/my-pipeline
curl -X POST -H 'Content-Type: application/xml' --data-binary @in.xml \
     'http://localhost:8080/api/v1/pipelines/my-pipeline/transform?option=param=value'
curl -X POST -H 'Content-Type: application/json' http://localhost:8080/api/v1/pipelines/my-pipeline/runs \
     -d '{"inputs": {"source": [{"contentType": "application/xml", "content": "<doc/>"}]},
          "options": {"param": "value"}}'
```

In the JSON envelopes, the `content` of XML, HTML, JSON and text documents is text, and the
content of binary documents is base64 (`"encoding": "base64"`). A run also returns the messages
that the pipeline produced (`[p:]message`, `p:message`, `xsl:message`).

Errors are returned as RFC 9457 problem details (`application/problem+json`) with the XProc
error code, the location and the failing step. Static errors give status 400, dynamic errors
422, and unknown pipelines 404.

Stored pipelines are kept in memory. The service is configured with Spring Boot properties
(for example `--xproc.allow-file-access=true`):

| Property                     | Default | Meaning                                         |
|------------------------------|---------|-------------------------------------------------|
| `xproc.allow-file-access`    | `false` | Allow pipelines to read and write `file:` URIs  |
| `xproc.allow-network-access` | `false` | Allow pipelines to access the network (and send mail) |
| `xproc.allow-os-access`      | `false` | Allow `p:os-exec` and the details of `p:os-info` |
| `xproc.locale`               |         | The value of the `p:locale` system property     |
| `xproc.max-pipelines`        | `1000`  | The maximum number of stored pipelines          |

Clients supply the pipelines they run, so file, network and operating system access are off
by default. With file access off, the file steps fail with their "access restrictions" error
codes. With OS access off, `p:os-exec` fails with `err:XC0033`, and `p:os-info` reports no
environment variables and leaves the user and working directory attributes empty. Do not turn
these settings on for a service that untrusted clients can reach. The service has no
authentication of its own, so put it behind a gateway that adds authentication before you
expose it.

## OpenAPI pipeline service

The `xproc-openapi` module is a Spring Boot 4 web service whose API is whatever an OpenAPI
3.1 (or 3.0) description says it is, and whose operations are implemented by XProc pipelines.
Both live in a configuration directory: the description (`openapi.yaml`, `openapi.yml` or
`openapi.json`) and, for every operation, the pipeline `<operationId>.xpl`. The description is
parsed with [Swagger Parser](https://github.com/swagger-api/swagger-parser), which also
resolves `$ref` references, and is loaded when the service starts.

```bash
java -jar xproc-openapi/target/xproc-openapi-1.0.0-SNAPSHOT.jar \
     --xproc.openapi.config-dir=xproc-openapi/example
curl 'http://localhost:8080/api/v1/greetings/Ann?lang=nl'
```

[`xproc-openapi/example`](xproc-openapi/example) is a small example service: parameters,
a request body transformed with a (cached) XSLT stylesheet, a multipart upload, the request
information, and an error.

Swagger UI ([springdoc](https://springdoc.org/)) shows the description at `/swagger-ui.html`,
where every operation can also be tried out. It displays the description file as it is, served
at `/openapi` (`xproc.openapi.description-path`), so a reloaded description shows up at once.
springdoc's own generated description (`/v3/api-docs`) is empty: the API is the description
in the configuration directory, not the controllers of the application. Swagger UI cannot
follow `$ref`s to other files, because only the description file itself is served. The
`springdoc.swagger-ui.*` properties configure the UI, and `springdoc.swagger-ui.enabled=false`
turns it off.

### Requests

The API paths are served under the path of the first server URL of the description (for
example `/api/v1`), or under `xproc.openapi.base-path`. A request is matched against the path
templates, most specific first; no match gives 404 and a path without the request method 405
(with an `Allow` header). Other paths (Swagger UI, `/openapi`, the actuator endpoints) are not
part of the API; an API served at the root takes only the paths that match its templates, so
avoid templates such as `/openapi` or `/swagger-ui/...` there. An operation without an `operationId` or without a pipeline file gives
501. Everything the request carries is passed to the pipeline:

| From the request | To the pipeline |
|------------------|-----------------|
| Path, query, header and cookie parameters of the operation | Options named after the parameter, in the namespace of its location (below) |
| The request body | The document on the input port `source`, parsed according to its `Content-Type` |
| Files of a `multipart/form-data` request | Documents on the input port named after the part, or else after the file name; several files of one part form a sequence |
| Other parts of a `multipart/form-data` request | Options named after the part, in the part namespace |
| Method, URI, path, headers, ... | Options in the request namespace (below) |

| Namespace | Options |
|-----------|---------|
| `http://www.armatiek.nl/ns/xproc/openapi/parameter/path` | path parameters |
| `http://www.armatiek.nl/ns/xproc/openapi/parameter/query` | query parameters |
| `http://www.armatiek.nl/ns/xproc/openapi/parameter/header` | header parameters |
| `http://www.armatiek.nl/ns/xproc/openapi/parameter/cookie` | cookie parameters |
| `http://www.armatiek.nl/ns/xproc/openapi/multipart/part` | non-file parts of a multipart request |
| `http://www.armatiek.nl/ns/xproc/openapi/request` | `method`, `uri`, `path`, `base-path`, `path-template`, `operation-id`, `query-string`, `scheme`, `server-name`, `server-port`, `remote-address`, `remote-user`, `content-type`, and the maps `headers` (lower-case names), `query`, `cookies` and `path-parameters`, each `map(xs:string, xs:string*)` |

For example:

```xml
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                version="3.1">
  <p:output port="result"/>
  <p:option name="path:name" as="xs:string" required="true"/>
  <p:option name="query:repeat" as="xs:integer" select="1"/>
  ...
</p:declare-step>
```

Only the options and input ports that the pipeline declares are passed. Values are passed as
`xs:untypedAtomic`, so they are converted to the type the pipeline declares (`as`). A
parameter whose schema is an array gives a sequence: repeated query parameters, or the items
of a comma (`simple`, `form` with `explode: false`), space (`spaceDelimited`) or pipe
(`pipeDelimited`) separated value. A missing parameter gets the `default` of its schema;
a missing required parameter gives 400, and so does a value that does not match the type of
its option, or a request body that cannot be parsed. Body parsing and the pipeline run
happen for every method that carries a body, not only POST.

### Responses

The document on the primary output port is the response body, serialized with the
serialization parameters of the port and the document. The `Content-Type` is the
`media-type` serialization parameter, or follows from the `method` parameter, or else is the
content type of the document. No document gives 204 No Content; more than one gives 500. The
pipeline can set the status and headers through the document properties `resp:status` (an
integer) and `resp:headers` (a map of header names to values) of the result, in the namespace
`http://www.armatiek.nl/ns/xproc/openapi/response`:

```xml
<p:set-properties properties="map{
    QName('http://www.armatiek.nl/ns/xproc/openapi/response', 'status'): 201}"/>
```

### Errors

Errors, including errors the pipeline does not handle, are returned as
[RFC 9457](https://www.rfc-editor.org/info/rfc9457/) problem details: `type` (`about:blank`),
`title`, `status`, `detail` and `instance`, plus for XProc errors the members `code`,
`causeCode`, `step`, `stepType`, `location`, `line`, `column` and `details` (the documents
passed to `p:error`). Unhandled pipeline errors give 500.

The format follows the serialization method of the primary output port of the pipeline: `json`
gives `application/problem+json`, and `xml`, `xhtml` and `html` give `application/problem+xml`
(namespace `urn:ietf:rfc:7807`). If the method is not specified, the format follows the
content type the result would have had, as in
[XProc's rules for choosing a method](https://spec.xproc.org/3.1/xproc/#serialization-method):
the content types declared on the primary output port (`content-types`), then the content
types of the responses of the operation in the description. Without either (and for errors
before an operation is known), an `Accept` header that asks for XML and not JSON gives XML;
otherwise the problem is JSON.

### Caching and concurrency

The description, the compiled pipelines and the compiled XSLT stylesheets are cached and
shared by all requests: one `XProcProcessor` runs all pipelines, and compiled pipelines and
stylesheets are immutable. A pipeline is compiled on first use. With
`xproc.openapi.check-modified` (the default), a changed description or pipeline file is loaded
again on the next request. Files that a pipeline imports or reads (other pipelines,
stylesheets included by a stylesheet) are not watched.

The actuator endpoint `/actuator/xproccaches` reports the caches (`GET`) and clears them
(`DELETE`): the description is loaded again, and pipelines and stylesheets are compiled again
on their next use. No restart is needed:

```bash
curl -X DELETE http://localhost:8080/actuator/xproccaches
```

### Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `xproc.openapi.config-dir` | `./config` | The directory with the description and the pipelines |
| `xproc.openapi.description` | | The description file name (default: `openapi.yaml`, `openapi.yml` or `openapi.json`) |
| `xproc.openapi.base-path` | | The path under which the API is served (default: the path of the first server URL) |
| `xproc.openapi.description-path` | `/openapi` | The path at which the description file is served (for Swagger UI) |
| `xproc.openapi.check-modified` | `true` | Load changed description and pipeline files again |
| `xproc.openapi.max-request-size` | `10485760` | The maximum size in bytes of a request body that is not multipart |
| `xproc.openapi.allow-file-access` | `true` | Allow pipelines to read and write `file:` URIs |
| `xproc.openapi.allow-network-access` | `true` | Allow pipelines to access the network |
| `xproc.openapi.allow-os-access` | `false` | Allow `p:os-exec` and the details of `p:os-info` |
| `xproc.openapi.xslt-cache-size` | `100` | The maximum number of compiled stylesheets kept |

The limits for multipart requests are Spring Boot's `spring.servlet.multipart.*` properties
(50 MB per file and 100 MB per request by default here). Unlike in `xproc-rest`, the pipelines
are part of the configuration rather than supplied by clients, so file and network access are
on by default. The service has no authentication of its own: put it behind a gateway that adds
it, and keep the actuator endpoints away from clients (for example with
`management.server.port`), since anyone who can reach `/actuator/xproccaches` can clear the
caches.

## Conformance

All features of the core language are implemented, together with all 51 required steps of
the standard step library. The optional step libraries are implemented as well:

* **File steps**: `p:directory-list`, `p:file-copy`, `p:file-delete`, `p:file-info`,
  `p:file-mkdir`, `p:file-move`, `p:file-create-tempfile` and `p:file-touch`.
* **Operating system steps**: `p:os-info` and `p:os-exec`.
* **Dynamic pipeline execution**: `p:run`.
* **Validation steps**: `p:validate-with-relax-ng`, `p:validate-with-schematron`,
  `p:validate-with-xml-schema`, `p:validate-with-nvdl`, `p:validate-with-json-schema` and
  `p:validate-with-dtd`.
* **Mail steps**: `p:send-mail`.
* **Text steps**: `p:markdown-to-html`.
* **Invisible XML**: `p:invisible-xml` (and its former name `p:ixml`).

PSVI support and the DTD compatibility attribute defaults of
`p:validate-with-relax-ng` (`dtd-attribute-values`) are not implemented.

The processor has been tested against the
[XProc test suite](https://codeberg.org/XProc/test-suite), with the suite's Docker-based HTTP
test server running. Of the 3133 tests that apply to this processor, 3104 pass (99.1%). Every
test of the file, operating system, `p:run`, validation, text and Invisible XML steps that
applies passes,
126 of the 127 `p:http-request` tests pass, and 53 of the 56 `p:import-functions` and
`p:function-library-importable` tests pass. The suite's other 230 tests were skipped
because they need optional features, a Windows host (`p:urify` Windows behaviour), XProc 3.2,
internet access, or the suite's mail test server. `p:send-mail` is covered by this project's
own tests against an in-process SMTP server instead.

Most of the 29 failing tests are cases where a test expects something other than what the
specification says. In those cases this implementation follows the specification:

* `p:http-request` with the `status-only` parameter returns an empty map of headers on the
  `report` port, as the specification requires (the test expects the response headers).
* `err:XS0103` (not `XS0106`) for a `p:import-functions` library that cannot be retrieved.
* `err:XS0094` (not `XS0038`) for a `p:variable` without `select`.
* `err:XS0088` (not `XS0091`) for a variable that shadows a static option.
* `err:XS0027` (not `XS0080`) for an option given both as an attribute and with
  `p:with-option`.
* `err:XD0065` (not `XD0001`) for a context sequence in `p:with-option`/value templates.

A few other edge cases remain open: some `p:cast-content-type` and override-content-type error
codes, the error code for a wrong argument type of `p:function-library-importable`, and a few
base URI and `use-when` corner cases.

To run the test suite yourself:

```bash
git clone https://codeberg.org/XProc/test-suite.git
./mvnw -pl xproc-core test -Dtest=TestSuiteTest -Dxproc.testsuite=$PWD/test-suite/test-suite/tests
```

The report is written to `xproc-core/target/testsuite-report.txt`. The runner sets up the
`t:file-environment` of the file step tests in the suite's `testfolder` directory. On
Unix-like systems it creates files marked as hidden with a name that starts with a dot.

The `p:http-request` tests of the suite need its HTTP test server on `localhost:8246`. The
runner checks whether that port is reachable: if it is, these tests run; if not, they are
skipped and the report says the server is not reachable. To run them, install Docker and start
the server from the test suite checkout (`docker compose up -d` in its `docker` directory, or
`./gradlew start`) before you run the suite.

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
* **`p:import-functions`**: XSLT packages (`application/xslt+xml`) and XQuery main and library
  modules (`application/xquery`). Without a `content-type` attribute, the type is derived from
  the file extension (`.xq`, `.xqy`, `.xquery` for XQuery), and XSLT is assumed otherwise. The
  public functions of an XSLT package and the non-private functions of an XQuery module are
  imported. XQuery functions can use the global variables of their module.
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
* **File steps**:
  * Only `file:` URIs are supported; other schemes raise the step's "scheme not supported"
    error. Access is subject to the file access setting of the configuration.
  * Results are a `c:result` element that contains the (resolved) URI given in the step's
    `href` or `target` option. For `p:file-create-tempfile` it contains the URI of the new file.
  * With `fail-on-error="false"`, errors are reported as a `c:error` element. Its `code` and
    `type` attributes give the error code and the step type in Clark notation
    (`{uri}local`), and its content is the error message.
  * `p:directory-list` and `p:file-info` report `content-type`, `readable`, `writable`,
    `last-modified` (UTC) and `size`. `hidden` is only reported when it is true. Symbolic
    links are followed. Objects that are neither files nor directories are listed as
    `c:other`. Entries are sorted by name.
  * `p:file-copy` and `p:file-move` copy or move a file into the target when the target is an
    existing directory or ends with a slash. A directory is always copied into the target
    directory. An invalid `max-depth` of `p:directory-list` raises `err:XD0028`.
  * The default location of `p:file-create-tempfile` is the JVM temporary directory.
* **Operating system steps**:
  * `p:os-info` returns the JVM's `os.arch`, `os.name`, `os.version`, `user.dir`,
    `user.name` and `user.home` properties, and one `c:environment` element per
    environment variable.
  * `p:os-exec` runs the command without a shell, in the JVM's working directory unless `cwd`
    is given. The exit status is the text of the `c:result` element on `exit-status`.
* **`p:run`**:
  * Each `p:run` compiles its pipeline when it runs. Errors in that pipeline raise
    `err:XC0200`.
  * If `p:run` has `p:run-input`s, its primary input must be the pipeline's primary input
    (`err:XC0206`). If it declares outputs, its primary output must be the pipeline's primary
    output (`err:XC0207`). A missing primary on either side also counts as a mismatch.
  * A `p:run-input` that provides no documents leaves the default connection of the
    pipeline's port in place, if it has one.
* **Mail steps** (`p:send-mail`, with [Jakarta Mail](https://eclipse-ee4j.github.io/angus-mail/)):
  * The first document is a message in the format of
    [draft-klyne-message-rfc822-xml](https://datatracker.ietf.org/doc/html/draft-klyne-message-rfc822-xml-03).
    Its root is `emx:Message` (namespace `URN:ietf:params:email-xml:`). The headers are
    elements in the `URN:ietf:params:rfc822:` namespace: `from`, `to`, `cc`, `bcc`,
    `reply-to` and `sender` contain `emx:Address` elements (with `emx:adrs`, a `mailto:` URI,
    and an optional `emx:name`) or a comma separated address list as text. `subject` is the
    subject, and any other element becomes a header with that name.
  * An `emx:content` with element content is sent as `text/html`. Otherwise its text is sent
    with the media type in its `type` attribute (default `text/plain`), in UTF-8. Without
    `emx:content`, the second document is the body.
  * Other documents are attachments, serialized as `p:store` would. The last segment of
    their base URI is the file name. With attachments the message is `multipart/mixed`.
  * Parameters: `host` (default `localhost`), `port` (default 25), `send-authorization`,
    and the implementation-defined `starttls`, `ssl` (SMTPS) and `debug` (Jakarta Mail
    protocol trace), all booleans that default to false. `auth` takes `username` and
    `password`. Sending mail counts as network access.
* **Text steps**: `p:markdown-to-html` converts
  [CommonMark](https://spec.commonmark.org/) with the GitHub Flavored Markdown table and
  strikethrough extensions ([commonmark-java](https://github.com/commonmark/commonmark-java)).
  The result is a `text/html` document with `html`, `head` and `body` elements in the XHTML
  namespace. The `parameters` option is not used.
* **Invisible XML** (`p:invisible-xml`, with
  [Markup Blitz](https://github.com/GuntherRademacher/markup-blitz)):
  * The grammar may be in the text form or the XML form of Invisible XML. Without a grammar,
    the source is parsed with the Invisible XML grammar itself. Generated parsers are cached
    (the 32 most recently used grammars).
  * Text sources are parsed as they are, JSON sources as their serialization, and other
    sources as their bytes decoded with the `charset` of their content type (default UTF-8).
  * The result is always one `application/xml` document. When the parse fails and
    `fail-on-error` is false, it is an `ixml` element with `ixml:state="failed"` and the error
    message. An ambiguous parse returns one of the parses, marked `ixml:state="ambiguous"`.
  * The `parameters` option is not used. The former step name `p:ixml` is supported.
* **Validation steps**:
  * The `xvrl` report format is supported by every validation step. `p:validate-with-schematron`
    also supports `svrl`, its default. Other values raise `err:XC0117`.
  * RELAX NG (XML and compact syntax) and NVDL use [Jing](https://github.com/relaxng/jing-trang).
    `dtd-id-idref-warnings` is supported; `dtd-attribute-values` is not. Within NVDL, W3C XML
    Schema validation is done by the JDK's validator.
  * Schematron uses [SchXslt2](https://github.com/schxslt/schxslt2) (`xslt2`/`xslt3` query
    bindings). Entries of the `c:compile` parameter are passed to the SchXslt2 transpiler.
    Other parameters are passed to the generated stylesheet as stylesheet parameters.
    `c:implementation` and `c:xvrl` are ignored. In XVRL reports, the `role` of an assertion
    sets the severity: `fatal`, `warning`/`warn`, `info`/`information`, otherwise `error`.
  * XML Schema uses the JDK's validator (XML Schema 1.0 only; other versions raise
    `err:XC0011`). Default attribute values are added to valid documents. There is no PSVI.
    The schemas on the `schema` port take precedence. `use-location-hints` adds the schemas
    named by `xsi:schemaLocation` and `xsi:noNamespaceSchemaLocation`, and `try-namespaces`
    tries to load a schema from each namespace URI. `xs:include` and `xs:import` references
    to documents on the `schema` port are resolved to those documents. Lax mode ignores a
    missing declaration of the document element.
  * DTD validation re-parses the serialized document with the JDK's validating parser.
  * JSON Schema uses the [networknt validator](https://github.com/networknt/json-schema-validator)
    and supports drafts 4, 6, 7, 2019-09 and 2020-12. A `$schema` in the schema wins; then
    `default-version` (a dialect URI or a name such as `2020-12` or `draft-07`) applies, and
    the default is 2020-12.

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
xproc-core/src/main/resources/nl/armatiek/xproc/*-steps.xpl declarations of the file, os,
                                                            validation, mail, text and ixml steps
xproc-cli/src/main/java/nl/armatiek/xproc/cli/Main.java     command line interface
xproc-rest/src/main/resources/static/openapi.yaml           REST interface specification
xproc-rest/src/main/java/nl/armatiek/xproc/rest/
    controller/  implementations of the generated API interfaces, error handling
    service/     compiling, storing and running pipelines; document conversion
xproc-openapi/src/main/java/nl/armatiek/xproc/openapi/
    api/         loading the OpenAPI description, operations and parameters, path matching
    web/         request to pipeline mapping, responses, RFC 9457 problem details
xproc-openapi/example/                                      an example configuration directory
```

`steps.xpl` contains the step declarations published as an ancillary file of the XProc 3.1
Standard Step Library specification (W3C Community Group).
