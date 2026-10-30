package nl.armatiek.xproc.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class XProcRestApplicationTest {

  private static final String API = "/api/v1";

  private static final String ADD_ATTRIBUTE = """
      <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
        <p:input port="source"/>
        <p:output port="result"/>
        <p:option name="value" select="'default'"/>
        <p:add-attribute match="/*" attribute-name="value" attribute-value="{$value}"
                         message="adding {$value}"/>
      </p:declare-step>""";

  @Autowired
  private MockMvc mvc;

  @Test
  void processorInfo() throws Exception {
    mvc.perform(get(API + "/processor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.xprocVersion").value("3.1"))
        .andExpect(jsonPath("$.saxonEdition").value("HE"))
        .andExpect(jsonPath("$.fileAccess").value(false));
  }

  @Test
  void pipelineLifecycle() throws Exception {
    mvc.perform(put(API + "/pipelines/lifecycle").contentType(MediaType.APPLICATION_XML)
            .content(ADD_ATTRIBUTE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("lifecycle"))
        .andExpect(jsonPath("$.inputs[0].name").value("source"))
        .andExpect(jsonPath("$.inputs[0].primary").value(true))
        .andExpect(jsonPath("$.outputs[0].name").value("result"))
        .andExpect(jsonPath("$.options[0].name").value("value"))
        .andExpect(jsonPath("$.options[0].required").value(false));
    mvc.perform(put(API + "/pipelines/lifecycle").contentType(MediaType.APPLICATION_XML)
            .content(ADD_ATTRIBUTE))
        .andExpect(status().isOk());
    mvc.perform(get(API + "/pipelines"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == 'lifecycle')]", hasSize(1)));
    mvc.perform(get(API + "/pipelines/lifecycle/source"))
        .andExpect(status().isOk())
        .andExpect(content().string(ADD_ATTRIBUTE));
    mvc.perform(delete(API + "/pipelines/lifecycle"))
        .andExpect(status().isNoContent());
    mvc.perform(get(API + "/pipelines/lifecycle"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404));
    mvc.perform(delete(API + "/pipelines/lifecycle"))
        .andExpect(status().isNotFound());
  }

  @Test
  void invalidPipelineId() throws Exception {
    mvc.perform(put(API + "/pipelines/bad id!").contentType(MediaType.APPLICATION_XML)
            .content(ADD_ATTRIBUTE))
        .andExpect(status().isBadRequest());
  }

  @Test
  void runStoredPipeline() throws Exception {
    store("run", ADD_ATTRIBUTE);
    mvc.perform(post(API + "/pipelines/run/runs").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"inputs": {"source": [{"content": "<doc/>", "baseUri": "http://example.com/in.xml"}]},
                 "options": {"value": "hello"}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primaryOutput").value("result"))
        .andExpect(jsonPath("$.outputs.result", hasSize(1)))
        .andExpect(jsonPath("$.outputs.result[0].contentType").value("application/xml"))
        .andExpect(jsonPath("$.outputs.result[0].encoding").value("text"))
        .andExpect(jsonPath("$.outputs.result[0].content").value(containsString(
            "<doc value=\"hello\"/>")))
        .andExpect(jsonPath("$.outputs.result[0].baseUri").value("http://example.com/in.xml"))
        .andExpect(jsonPath("$.messages[0].message").value("adding hello"));
  }

  @Test
  void runWithXPathOptionAndNamespaces() throws Exception {
    store("xpath", """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" xmlns:ex="http://example.com"
                        xmlns:xs="http://www.w3.org/2001/XMLSchema" version="3.1">
          <p:output port="result"/>
          <p:option name="ex:numbers" as="xs:integer*"/>
          <p:identity>
            <p:with-input><total>{sum($ex:numbers)}</total></p:with-input>
          </p:identity>
        </p:declare-step>""");
    mvc.perform(post(API + "/pipelines/xpath/runs").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"xpathOptions": {"e:numbers": "(1, 2, 3)"},
                 "namespaces": {"e": "http://example.com"}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outputs.result[0].content").value(endsWith(">6</total>")));
  }

  @Test
  void jsonAndBinaryDocuments() throws Exception {
    store("json", """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
          <p:input port="source" content-types="json"/>
          <p:output port="result" sequence="true"/>
          <p:identity name="json"/>
          <p:cast-content-type name="cast" content-type="application/octet-stream"/>
          <p:identity>
            <p:with-input>
              <p:pipe step="json"/>
              <p:pipe step="cast"/>
            </p:with-input>
          </p:identity>
        </p:declare-step>""");
    mvc.perform(post(API + "/pipelines/json/runs").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"inputs": {"source": [{"contentType": "application/json",
                                        "content": "{\\"a\\": 1}",
                                        "properties": {"Q{http://example.com}p": "v"}}]}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outputs.result", hasSize(2)))
        .andExpect(jsonPath("$.outputs.result[0].contentType").value("application/json"))
        .andExpect(jsonPath("$.outputs.result[0].properties['Q{http://example.com}p']")
            .value("v"))
        .andExpect(jsonPath("$.outputs.result[1].encoding").value("base64"));
  }

  @Test
  void transform() throws Exception {
    store("transform", ADD_ATTRIBUTE);
    mvc.perform(post(API + "/pipelines/transform/transform").param("option", "value=raw")
            .contentType(MediaType.APPLICATION_XML).content("<doc/>"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", startsWith("application/xml")))
        .andExpect(content().string(containsString("<doc value=\"raw\"/>")));
  }

  @Test
  void transformWithoutOutputDocuments() throws Exception {
    store("sink", """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
          <p:input port="source"/>
          <p:output port="result" sequence="true"/>
          <p:sink/>
          <p:identity><p:with-input><p:empty/></p:with-input></p:identity>
        </p:declare-step>""");
    mvc.perform(post(API + "/pipelines/sink/transform").contentType(MediaType.TEXT_PLAIN)
            .content("text"))
        .andExpect(status().isNoContent());
  }

  @Test
  void adHocRun() throws Exception {
    mvc.perform(post(API + "/runs").contentType(MediaType.APPLICATION_JSON).content("""
            {"pipeline": {"source": "<p:declare-step xmlns:p='http://www.w3.org/ns/xproc' version='3.1'><p:output port='result'/><p:option name='s' static='true'/><p:identity><p:with-input><r>{$s}</r></p:with-input></p:identity></p:declare-step>",
                          "staticOptions": {"s": "static value"}}}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outputs.result[0].content")
            .value(endsWith("<r>static value</r>")));
  }

  @Test
  void staticError() throws Exception {
    mvc.perform(put(API + "/pipelines/static").contentType(MediaType.APPLICATION_XML)
            .content("""
                <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" version="3.1">
                  <p:no-such-step/>
                </p:declare-step>"""))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value(startsWith("Q{http://www.w3.org/ns/xproc-error}XS")))
        .andExpect(jsonPath("$.code").value(endsWith("XS0044")));
  }

  @Test
  void dynamicError() throws Exception {
    store("error", """
        <p:declare-step xmlns:p="http://www.w3.org/ns/xproc" xmlns:ex="http://example.com"
                        version="3.1">
          <p:output port="result"/>
          <p:error name="fail" code="ex:E1">
            <p:with-input><message>failed</message></p:with-input>
          </p:error>
        </p:declare-step>""");
    mvc.perform(post(API + "/pipelines/error/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("Q{http://example.com}E1"))
        .andExpect(jsonPath("$.step").value("fail"));
  }

  @Test
  void fileAccessIsForbiddenByDefault() throws Exception {
    store("load", ADD_ATTRIBUTE);
    mvc.perform(post(API + "/pipelines/load/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"inputs\": {\"source\": [{\"href\": \"file:///etc/hosts\"}]}}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("Q{http://www.w3.org/ns/xproc-error}XD0021"));
  }

  @Test
  void badRequests() throws Exception {
    store("bad", ADD_ATTRIBUTE);
    mvc.perform(post(API + "/pipelines/bad/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"inputs\": {\"nope\": []}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("nope")));
    mvc.perform(post(API + "/pipelines/bad/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"options\": {\"x:value\": \"1\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("prefix")));
    mvc.perform(post(API + "/pipelines/bad/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{not json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void specificationIsServed() throws Exception {
    mvc.perform(get("/openapi.yaml"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("title: XProc REST API")));
  }

  private void store(String id, String pipeline) throws Exception {
    mvc.perform(put(API + "/pipelines/" + id).contentType(MediaType.APPLICATION_XML)
            .content(pipeline))
        .andExpect(status().is2xxSuccessful());
  }
}
