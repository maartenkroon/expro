# CLAUDE.md

Expro is an XProc 3.1 processor for Java 25, built on Saxon 13.1. It covers the core
language, all required standard steps and the optional step libraries (file, os, p:run,
validation, mail, text, ixml). README.md is the user-facing documentation. This file holds
the rules and decisions for working on the code.

## Rules

- **Commit only when asked.** Every commit must have author and committer `maartenkroon`
  (name and email both `maartenkroon`). Never use the user's real email address. Commit with:
  `git -c user.name="maartenkroon" -c user.email="maartenkroon" commit ...`
  and end the message with the `Co-Authored-By: Claude ...` trailer.
- **Stage files by name, never `git add -A`.** The user keeps untracked files of their own in
  the tree (for example `xproc-core/src/test/java/nl/armatiek/xproc/ApiTest.java`). Don't
  commit, edit or delete them.
- **Don't push** unless asked. The remote is `origin` (github.com/maartenkroon/expro).
- **The specification wins.** When a test in the conformance suite contradicts the spec,
  follow the spec and list the case under Conformance in README.md.
- **Keep README.md current** with every feature: the module table, the conformance list and
  numbers, and the "Implementation-defined features" section. The XProc specs require
  implementation-defined behaviour to be documented.

## Modules

| Module | What it is |
|---|---|
| `xproc-core` | The library. Public API in `nl.armatiek.xproc`; everything else is under `internal/`. |
| `xproc-cli` | Command line interface, shaded into an `-all` jar. `--safe` turns off file, network and OS access. |
| `xproc-rest` | Spring Boot 4.1.2 REST service, **schema first**. Edit `src/main/resources/static/openapi.yaml`; openapi-generator 7.26.0 generates the interfaces (`interfaceOnly`, `useSpringBoot4`) and the controllers implement them. Clients upload pipelines, so file, network and OS access default to off. |
| `xproc-openapi` | Spring Boot service whose API is an OpenAPI 3.1 description in a config directory (`xproc.openapi.config-dir`). Each operation runs `<operationId>.xpl` from that directory. The example configuration is in `xproc-openapi/example`. The pipelines are part of the configuration, so file and network access default to on. |

## Build and test

```bash
./mvnw clean install                 # all modules and unit tests (add -o to work offline)
./mvnw install -P Saxon-EE           # profiles: Saxon-HE (default), Saxon-PE, Saxon-EE
```

- The PE and EE jars come from the `repos-saxonica` repository (https://maven.saxonica.com/maven)
  declared in the parent POM. The edition profiles are in `xproc-core/pom.xml`.
- **Conformance suite:** a checkout is at `/home/maarten/Projects/test-suite`. The runner is
  `nl.armatiek.xproc.testsuite.TestSuiteRunner TESTS-DIR [REGEX] [REPORT-FILE]`, and
  `TestSuiteTest` wraps it for Maven (see README). For a quick run, use the test classpath:
  `java -cp target/classes:target/test-classes:<dependency classpath> nl.armatiek.xproc.testsuite.TestSuiteRunner /home/maarten/Projects/test-suite/test-suite/tests '<regex>'`.
- The `p:http-request` tests need the suite's Docker HTTP server on `localhost:8246`; without
  it they are skipped. `nw-http-request-130` sometimes fails on a connection error; run it
  again before you treat it as a regression.
- **Baseline** with the HTTP server running: 3133 applicable tests, 3104 passed, 29 known
  failures (listed in README). Without the server: 2978 passed, 28 failed. After a core change,
  run the full suite and compare the failures by name with these.

## Code conventions

- Java 25. Indent with 2 spaces (4 for continuation lines) and keep lines to 100 characters
  or less.
- Every class gets a Javadoc comment saying what it is for. Comment sparingly elsewhere, and
  only to explain why.
- Prefer records for value types and `final` utility classes with a private constructor.
  Imports go in alphabetical blocks separated by blank lines: `java.*` and `javax.*` first,
  then `org.*`, then the rest (`io.*`, `jakarta.*`, `net.sf.saxon.*`, `nl.armatiek.*`, ...).
- Errors are `XProcException`s with spec error codes: `XProcException.xs/xd/xc(number, message)`
  for static, dynamic and step errors. Use the exact codes the spec gives. Use `.setCallerError(true)`
  when the caller's input is at fault (wrong option types, a missing required option); the
  `xproc-openapi` service answers those with HTTP 400.
- Keep the public API small and documented. New configuration goes in `XProcConfiguration`:
  a fluent setter and getter, and copy it in `copy()`.
- The compiler runs with `-Xlint:all,-serial,-processing`. Keep the build free of warnings.

## Core architecture

- **Saxon Processor.** `ProcessorContext` creates it with
  `new Processor(Configuration.newLicensedConfiguration())`, which picks the best licensed
  edition (EE, then PE, otherwise HE). Don't switch to `newConfiguration()`: it picks the
  edition from the jar without checking for a license. A `Processor` passed to
  `XProcConfiguration.setSaxonProcessor()` is used as it is.
- **Thread safety.** `XProcProcessor` and `XProcPipeline` are thread-safe and immutable, and
  each run gets its own `Executor`. Shared state in `ProcessorContext` must be thread-safe,
  for example the weak, synchronized `DocumentRegistry` and the `XsltCache`.
- **XSLT cache** (`internal/XsltCache`). An LRU of compiled `XsltExecutable`s used by `p:xslt`
  and Schematron. The key is a SHA-256 of the serialized stylesheet, its base URI and its
  static or compile parameters. Included and imported modules are not part of the key; that is
  what `XProcProcessor.clearCaches()` is for. Route new XSLT compilations through
  `pc.xsltCache.get(...)`.
- **Security settings** `allowFileAccess`, `allowNetworkAccess` and `allowOsAccess` are checked
  in `ProcessorContext.checkAccess`. The schemes http, https, smtp and smtps count as network.
  Every new step that touches files, the network or the OS must honour these settings.

### Adding a step or step library

1. Put the library's `*-steps.xpl` from the spec (its ancillary file) in
   `xproc-core/src/main/resources/nl/armatiek/xproc/`. Add only deliberate, commented
   extensions to it, such as the `p:ixml` alias.
2. Add the file name to `PipelineParser.STEP_LIBRARIES`.
3. Implement the steps in a `final` class in `internal/steps` with a
   `static void register(StepRegistry r)`, and call it from `StandardSteps`. Steps read their
   inputs and options through `StepContext`; use the helpers in `StepSupport`.
4. Add the step names to `KNOWN` in `TestSuiteRunner`, and the suite's feature tokens (such as
   `p:ixml` or `p-validate-with-xsd`) to `FEATURES`.
5. Write a unit test class (they extend `TestSupport`: `result()`, `assertError()`), run the
   suite's tests for the library, then the full suite.
6. Update README.md: the intro, the Conformance section and its numbers, the
   implementation-defined notes, and the project layout.

## Dependency decisions

- **One Saxon on the classpath.** Exclude Saxon-HE from dependencies that bring their own,
  such as Jing. Reject libraries that need another Saxon major version: CoffeeFilter needs
  Saxon 12, which is why ixml uses Markup Blitz.
- **Libraries per area:**

  | Area | Library |
  |---|---|
  | RELAX NG and NVDL | Jing, with XSD through `JaxpXsdSchemaReaderFactory` |
  | Schematron | SchXslt2 |
  | JSON Schema | networknt json-schema-validator (Jackson 3) |
  | `p:send-mail` | Angus Mail |
  | `p:markdown-to-html` | commonmark-java with the GFM extensions |
  | Invisible XML | Markup Blitz |
  | OpenAPI parsing | swagger-parser-v3 |
  | Reports | XVRL, plus SVRL for Schematron |

- **Jackson:** the core uses Jackson 3 (`tools.jackson`). Jackson 2 is allowed only in
  `xproc-openapi`, where swagger-parser needs it.
- Manage dependency versions as properties in the parent POM's `dependencyManagement`.

## xproc-openapi specifics

- Option namespaces are in `OpenApiNs`: `…/parameter/{path,query,header,cookie}`,
  `…/multipart/part`, `…/request` and `…/response` (the `resp:status` and `resp:headers`
  document properties). They are part of the public contract, so don't rename them.
- Only options and ports the pipeline declares are passed, because undeclared ones raise
  XS0031 and XS0114. Values are passed as `xs:untypedAtomic`.
- Query parameters are read from `getQueryString()`, never from `getParameter*()`, so a form
  body is never consumed by accident.
- The problem-detail format follows the primary output's serialization method, then the port's
  `content-types`, then the operation's response content types, then `Accept`; the default is
  JSON.
- MockMvc pitfalls in tests:
  - `.param()` sets no query string; put the query in the URL.
  - Use `MockPart`, not `.file()`, so `getParts()` sees the files.
  - Pass `URI.create(...)` for URLs that are already percent-encoded.
  - Run the real jar once with curl for changes to request handling.
- In example and test pipelines, declare `xmlns:xs` when using `xs:` types. Put
  `expand-text="false"` on `p:inline` JSON such as `{}`, and `exclude-inline-prefixes="#all"`
  to keep namespace declarations out of results.
