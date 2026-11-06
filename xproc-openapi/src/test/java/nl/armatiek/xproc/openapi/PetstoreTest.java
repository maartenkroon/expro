package nl.armatiek.xproc.openapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the pet store of examples/petstore. The tests share the store, so each test uses its
 * own entities or only checks what the others leave alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PetstoreTest {

  private static final String API = "/api/v3";

  @TempDir
  static Path configDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws IOException {
    Examples.copy("petstore", configDir);
    registry.add("xproc.openapi.config-dir", configDir::toString);
  }

  @Autowired
  MockMvc mvc;

  @Test
  void petByIdInJsonAndXml() throws Exception {
    mvc.perform(get(API + "/pet/1"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/json; charset=utf-8"))
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.name").value("Cat 1"))
        .andExpect(jsonPath("$.category.name").value("Cats"))
        .andExpect(jsonPath("$.photoUrls[1]").value("url2"))
        .andExpect(jsonPath("$.tags[0].name").value("tag1"));
    mvc.perform(get(API + "/pet/1").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/xml; charset=utf-8"))
        .andExpect(content().string(containsString("<name>Cat 1</name>")))
        .andExpect(content().string(containsString("<photoUrl>url1</photoUrl>")));
  }

  @Test
  void unknownOrInvalidPet() throws Exception {
    mvc.perform(get(API + "/pet/999"))
        .andExpect(status().isNotFound())
        .andExpect(content().string("Pet not found"));
    mvc.perform(get(API + "/pet/abc"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void findPets() throws Exception {
    mvc.perform(get(API + "/pet/findByStatus?status=pending,sold"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", hasItems(3, 5, 6)))
        .andExpect(jsonPath("$[*].status", not(hasItems("available"))));
    mvc.perform(get(API + "/pet/findByTags?tags=tag4&tags=none"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", hasItems(3, 6, 9, 10)))
        .andExpect(jsonPath("$.length()").value(4));
    mvc.perform(get(API + "/pet/findByTags?tags="))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("No tags provided. Try again?"));
  }

  @Test
  void addAndUpdatePets() throws Exception {
    // Without an id, the pet gets the next one
    mvc.perform(post(API + "/pet").contentType(MediaType.APPLICATION_JSON).content("""
        {"name": "doggie", "photoUrls": ["p1"], "tags": [{"id": 0, "name": "new"}],
         "status": "available", "unknown": true}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.name").value("doggie"))
        .andExpect(jsonPath("$.unknown").doesNotExist());
    mvc.perform(post(API + "/pet").contentType(MediaType.APPLICATION_XML)
        .accept(MediaType.APPLICATION_XML).content("""
        <pet><id>100</id><name>Xml pet</name><status>sold</status>
          <photoUrls><photoUrl>u</photoUrl></photoUrls></pet>"""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<id>100</id>")));
    mvc.perform(put(API + "/pet").contentType(MediaType.APPLICATION_JSON)
        .content("{\"id\": 100, \"name\": \"Renamed\", \"photoUrls\": []}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"))
        .andExpect(jsonPath("$.status").doesNotExist());
    mvc.perform(put(API + "/pet").contentType(MediaType.APPLICATION_JSON)
        .content("{\"id\": 998, \"name\": \"Nobody\", \"photoUrls\": []}"))
        .andExpect(status().isNotFound());
    mvc.perform(post(API + "/pet").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("No Pet provided. Try again?"));
  }

  @Test
  void formDataAndUpdateWithForm() throws Exception {
    mvc.perform(post(API + "/pet").contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .content("id=101&name=Formy&photoUrls=a&photoUrls=b&tags=fluffy&status=pending"
            + "&category=%7B%22id%22%3A2%2C%22name%22%3A%22Cats%22%7D"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(101))
        .andExpect(jsonPath("$.photoUrls.length()").value(2))
        .andExpect(jsonPath("$.tags[0].name").value("fluffy"))
        .andExpect(jsonPath("$.category.name").value("Cats"));
    mvc.perform(post(API + "/pet/101?name=Formier"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Formier"))
        .andExpect(jsonPath("$.status").doesNotExist());
    mvc.perform(post(API + "/pet/101"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("No Name provided. Try again?"));
    mvc.perform(post(API + "/pet/997?name=x"))
        .andExpect(status().isNotFound());
  }

  @Test
  void uploadAndDelete() throws Exception {
    mvc.perform(post(API + "/pet").contentType(MediaType.APPLICATION_JSON)
        .content("{\"id\": 102, \"name\": \"Photogenic\", \"photoUrls\": []}"))
        .andExpect(status().isOk());
    mvc.perform(post(API + "/pet/102/uploadImage")
        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1, 2, 3}))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrls[0]", startsWith("file:")))
        .andExpect(jsonPath("$.photoUrls[0]", containsString("/data/images/102-")));
    mvc.perform(post(API + "/pet/102/uploadImage"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("No file uploaded"));
    mvc.perform(delete(API + "/pet/102"))
        .andExpect(status().isOk())
        .andExpect(content().string("Pet deleted"));
    mvc.perform(get(API + "/pet/102"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ordersAndInventory() throws Exception {
    mvc.perform(get(API + "/store/inventory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.placed").value(100))
        .andExpect(jsonPath("$.testing").doesNotExist());
    mvc.perform(post(API + "/store/order").contentType(MediaType.APPLICATION_JSON).content("""
        {"id": 200, "petId": 1, "quantity": 7, "shipDate": "2026-11-06T10:00:00Z",
         "status": "testing", "complete": false}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(false));
    mvc.perform(get(API + "/store/inventory"))
        .andExpect(jsonPath("$.testing").value(7));
    mvc.perform(get(API + "/store/order/200").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<quantity>7</quantity>")));
    mvc.perform(delete(API + "/store/order/200"))
        .andExpect(status().isOk());
    mvc.perform(get(API + "/store/order/200"))
        .andExpect(status().isNotFound())
        .andExpect(content().string("Order not found"));
  }

  @Test
  void users() throws Exception {
    mvc.perform(get(URI.create(API + "/user/user%3F10")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(11))
        .andExpect(jsonPath("$.username").value("user?10"));
    mvc.perform(post(API + "/user").contentType(MediaType.APPLICATION_JSON)
        .content("{\"id\": 300, \"username\": \"theUser\", \"userStatus\": 1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userStatus").value(1));
    mvc.perform(post(API + "/user/createWithList").contentType(MediaType.APPLICATION_JSON)
        .content("[{\"username\": \"listed1\"}, {\"username\": \"listed2\"}]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    mvc.perform(get(API + "/user/listed2")).andExpect(status().isOk());
    // The user name may change
    mvc.perform(put(API + "/user/listed1").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\": \"renamed1\", \"phone\": \"12\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phone").value("12"));
    mvc.perform(get(API + "/user/listed1")).andExpect(status().isNotFound());
    mvc.perform(get(API + "/user/renamed1")).andExpect(status().isOk());
    mvc.perform(delete(API + "/user/renamed1")).andExpect(status().isOk());
    mvc.perform(get(API + "/user/renamed1"))
        .andExpect(status().isNotFound())
        .andExpect(content().string("User not found"));
  }

  @Test
  void loginAndLogout() throws Exception {
    mvc.perform(get(API + "/user/login?username=user1&password=secret"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Rate-Limit", "5000"))
        .andExpect(header().string("X-Expires-After",
            matchesPattern("\\w{3} \\w{3} \\d{2} \\d{2}:\\d{2}:\\d{2} UTC \\d{4}")))
        .andExpect(content().string(startsWith("Logged in user session: ")));
    mvc.perform(get(API + "/user/logout"))
        .andExpect(status().isOk())
        .andExpect(content().string("User logged out"));
  }
}
