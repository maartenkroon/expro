package nl.armatiek.xproc.openapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;
import nl.armatiek.xproc.XProcProcessor;

/** Runs the example service of the example directory. */
@SpringBootTest
@AutoConfigureMockMvc
class XProcOpenApiApplicationTest {

  @TempDir
  static Path configDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    try (Stream<Path> files = Files.list(Path.of("example"))) {
      for (Path f : files.toList()) {
        Files.copy(f, configDir.resolve(f.getFileName()));
      }
    }
    registry.add("xproc.openapi.config-dir", configDir::toString);
  }

  @Autowired
  MockMvc mvc;

  @Autowired
  XProcProcessor processor;

  @Test
  void pathQueryAndHeaderParameters() throws Exception {
    mvc.perform(get("/api/v1/greetings/Ann?lang=nl&repeat=2")
        .header("X-Request-Id", "42"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/xml; charset=utf-8"))
        .andExpect(content().string(containsString("request-id=\"42\"")))
        .andExpect(content().string(containsString("method=\"GET\"")))
        .andExpect(content().string(containsString(">Hallo Ann! Hallo Ann!</greeting>")));
  }

  @Test
  void parameterDefaults() throws Exception {
    mvc.perform(get("/api/v1/greetings/Bob%20Smith"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("lang=\"en\"")))
        .andExpect(content().string(containsString(">Hello Bob Smith!</greeting>")));
  }

  @Test
  void invalidParameterValueIsBadRequest() throws Exception {
    mvc.perform(get("/api/v1/greetings/Ann?repeat=many"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+xml; charset=utf-8"))
        .andExpect(content().string(containsString("<status>400</status>")));
  }

  @Test
  void bodyIsTheSourceDocumentAndStylesheetsAreCached() throws Exception {
    for (int i = 0; i < 3; i++) {
      mvc.perform(post("/api/v1/transform").contentType(MediaType.APPLICATION_XML)
          .content("<doc><a/></doc>"))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString(
              "<transformed elements=\"2\"><doc><a/></doc></transformed>")));
    }
    assertEquals(1, processor.getCachedStylesheetCount());
  }

  @Test
  void malformedBodyIsBadRequest() throws Exception {
    // The output port declares content-types="xml", so the problem is XML
    mvc.perform(post("/api/v1/transform").contentType(MediaType.APPLICATION_XML)
        .content("<doc>"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+xml; charset=utf-8"))
        .andExpect(content().string(containsString("<problem xmlns=\"urn:ietf:rfc:7807\">")))
        .andExpect(content().string(containsString("<instance>/api/v1/transform</instance>")));
  }

  @Test
  void multipartFilesAndFields() throws Exception {
    mvc.perform(multipart("/api/v1/uploads")
        .part(new MockPart("document", "one.xml", "<one/>".getBytes(),
            MediaType.APPLICATION_XML))
        .part(new MockPart("document", "two.txt", "two".getBytes(), MediaType.TEXT_PLAIN))
        .part(new MockPart("ignored", "x.txt", "x".getBytes(), MediaType.TEXT_PLAIN))
        .part(new MockPart("comment", "Two files".getBytes())))
        .andExpect(status().isCreated())
        .andExpect(header().string("Content-Type", startsWith("application/json")))
        .andExpect(jsonPath("$.comment").value("Two files"))
        .andExpect(jsonPath("$.files.length()").value(2))
        .andExpect(jsonPath("$.files[0].content-type").value("application/xml"))
        .andExpect(jsonPath("$.files[1].base-uri").value("http://localhost/api/v1/two.txt"));
  }

  @Test
  void requestInformation() throws Exception {
    mvc.perform(get(URI.create("/api/v1/echo?tags=a&tags=b%20c")).cookie(new Cookie("session", "s1"))
        .header("User-Agent", "test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.method").value("GET"))
        .andExpect(jsonPath("$.uri").value("http://localhost/api/v1/echo?tags=a&tags=b%20c"))
        .andExpect(jsonPath("$.path").value("/api/v1/echo"))
        .andExpect(jsonPath("$.path-template").value("/echo"))
        .andExpect(jsonPath("$.operation-id").value("echoRequest"))
        .andExpect(jsonPath("$.tags[1]").value("b c"))
        .andExpect(jsonPath("$.session").value("s1"))
        .andExpect(jsonPath("$.user-agent").value("test"))
        .andExpect(jsonPath("$.query.tags[0]").value("a"));
  }

  @Test
  void pipelineErrorIsAProblem() throws Exception {
    // The output port has serialization method xml, so the problem is XML
    mvc.perform(get("/api/v1/fail"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string("Content-Type", "application/problem+xml; charset=utf-8"))
        .andExpect(content().string(containsString("<title>Internal Server Error</title>")))
        .andExpect(content().string(containsString("<code>Q{http://example.com/errors}always"
            + "</code>")))
        .andExpect(content().string(containsString(">This operation always fails</message>")));
  }

  @Test
  void unknownPathIsNotFound() throws Exception {
    mvc.perform(get("/api/v1/nothing"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(404));
    mvc.perform(get("/elsewhere").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+xml; charset=utf-8"));
  }

  @Test
  void otherMethodIsNotAllowed() throws Exception {
    mvc.perform(delete("/api/v1/echo"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "GET"));
  }

  @Test
  void changedDescriptionIsLoadedAgain() throws Exception {
    Path description = configDir.resolve("openapi.yaml");
    String yaml = Files.readString(description).replace("paths:\n", """
        paths:
          /missing:
            get:
              operationId: missingPipeline
              responses:
                "200":
                  description: Nothing
        """);
    Files.writeString(description, yaml);
    Files.setLastModifiedTime(description, FileTime.fromMillis(System.currentTimeMillis()
        + 5000));
    mvc.perform(get("/api/v1/missing"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.detail").value(containsString("missingPipeline.xpl")));
  }

  @Test
  void concurrentRequests() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        String name = "n" + i;
        results.add(pool.submit(() -> {
          String body = mvc.perform(get("/api/v1/greetings/" + name)).andReturn().getResponse()
              .getContentAsString();
          return body.contains(">Hello " + name + "!</greeting>") ? 1 : 0;
        }));
        results.add(pool.submit(() -> mvc.perform(post("/api/v1/transform")
            .contentType(MediaType.APPLICATION_XML).content("<doc id='" + name + "'/>"))
            .andReturn().getResponse().getContentAsString()
            .contains("<doc id=\"" + name + "\"/>") ? 1 : 0));
      }
      int ok = 0;
      for (Future<Integer> f : results) {
        ok += f.get();
      }
      assertEquals(128, ok);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void cachesCanBeCleared() throws Exception {
    mvc.perform(get("/api/v1/greetings/Ann")).andExpect(status().isOk());
    mvc.perform(get("/actuator/xproccaches"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.compiledPipelines").value(org.hamcrest.Matchers.greaterThan(0)));
    mvc.perform(delete("/actuator/xproccaches"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.compiledPipelines").value(0))
        .andExpect(jsonPath("$.compiledStylesheets").value(0));
    mvc.perform(get("/api/v1/greetings/Ann")).andExpect(status().isOk());
  }
}
