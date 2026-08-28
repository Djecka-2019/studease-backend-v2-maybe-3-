package tech.studease.studease.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesTest {

  @Autowired private MockMvc mvc;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/admin/tests",
        "/api/v1/admin/collections",
        "/api/v1/admin/questions/by-test/00000000-0000-0000-0000-000000000000",
        "/api/v1/admin/samples/00000000-0000-0000-0000-000000000000",
        "/api/v1/admin/sessions/00000000-0000-0000-0000-000000000000",
        "/api/v1/auth/current",
        "/api/v1/some/unmapped/path"
      })
  void protectedRoutesRejectAnonymousWithJson401(String path) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/auth/register"})
  void authEntryPointsAreNotBlockedBySecurity(String path) throws Exception {
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void studentTestFlowIsReachableWithoutAuth() throws Exception {
    mvc.perform(get("/api/v1/tests/00000000-0000-0000-0000-000000000000"))
        .andExpect(status().isNotFound());
  }

  @Test
  void invalidBearerTokenIsTreatedAsAnonymous() throws Exception {
    mvc.perform(
            get("/api/v1/admin/tests").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void securityHeadersArePresent() throws Exception {
    mvc.perform(get("/api/v1/admin/tests"))
        .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
  }
}
