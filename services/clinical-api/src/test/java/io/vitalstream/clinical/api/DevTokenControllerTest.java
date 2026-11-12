package io.vitalstream.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.vitalstream.clinical.application.TokenIssuer;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import io.vitalstream.clinical.infrastructure.security.SecurityConfig;
import io.vitalstream.clinical.infrastructure.security.TestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dev token endpoint exists only with CLINICAL_DEV_TOKENS_ENABLED=true, APP_ENV=dev and JWKS
 * mode off (CONTRACTS 4.2).
 */
class DevTokenControllerTest {

  private static final String BODY =
      "{\"sub\":\"nurse.levi\",\"name\":\"Nurse Levi\",\"roles\":[\"CLINICIAN\"]}";

  @TestConfiguration
  static class Beans {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  @Nested
  @WebMvcTest(DevTokenController.class)
  @Import({SecurityConfig.class, Beans.class})
  @EnableConfigurationProperties(ClinicalProperties.class)
  @TestPropertySource(
      properties = {"vitalstream.app-env=dev", "vitalstream.dev-tokens-enabled=true"})
  class InDev {

    @Autowired MockMvc mvc;
    @Autowired JwtDecoder decoder;

    @Test
    void issuesAnHs256TokenTheResourceServerAccepts() throws Exception {
      MvcResult result =
          mvc.perform(
                  post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.token").isNotEmpty())
              .andExpect(jsonPath("$.expiresAt").isNotEmpty())
              .andReturn();
      JsonNode json =
          JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());

      Jwt jwt = decoder.decode(json.path("token").asString());

      assertThat(jwt.getSubject()).isEqualTo("nurse.levi");
      assertThat(jwt.getClaimAsString("name")).isEqualTo("Nurse Levi");
      assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CLINICIAN");
      assertThat(jwt.getClaimAsString("iss")).isEqualTo("vitalstream-dev");
      assertThat(jwt.getAudience()).containsExactly("clinical-api");
      assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
      assertThat(Duration.between(Instant.now(), jwt.getExpiresAt()))
          .isBetween(Duration.ofHours(8).minusSeconds(30), Duration.ofHours(8).plusSeconds(5));
      assertThat(Instant.parse(json.path("expiresAt").asString())).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void nameIsOptional() throws Exception {
      MvcResult result =
          mvc.perform(
                  post("/api/v1/dev/token")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"sub\":\"copilot\",\"roles\":[\"SERVICE\"]}"))
              .andExpect(status().isOk())
              .andReturn();
      String token =
          JsonMapper.builder()
              .build()
              .readTree(result.getResponse().getContentAsString())
              .path("token")
              .asString();

      assertThat(decoder.decode(token).getClaimAsString("name")).isNull();
    }

    @Test
    void rejectsMissingSubEmptyRolesAndUnknownRoles() throws Exception {
      for (String bad :
          new String[] {
            "{\"roles\":[\"VIEWER\"]}",
            "{\"sub\":\"\",\"roles\":[\"VIEWER\"]}",
            "{\"sub\":\"x\",\"roles\":[]}",
            "{\"sub\":\"x\"}",
            "{\"sub\":\"x\",\"roles\":[\"ADMIN\"]}"
          }) {
        mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
      }
    }

    @Test
    void onlyPostIsSupported() throws Exception {
      mvc.perform(get("/api/v1/dev/token")).andExpect(status().isUnauthorized());
    }
  }

  /** Opt-in: dev alone is not enough, the flag defaults to off everywhere. */
  @Nested
  @WebMvcTest(DevTokenController.class)
  @Import({SecurityConfig.class, Beans.class})
  @EnableConfigurationProperties(ClinicalProperties.class)
  @TestPropertySource(properties = "vitalstream.app-env=dev")
  class FlagMissingInDev {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void noDevBeansAreRegistered() {
      assertThat(context.getBeanNamesForType(DevTokenController.class)).isEmpty();
      assertThat(context.getBeanNamesForType(TokenIssuer.class)).isEmpty();
    }

    @Test
    void theRouteAnswers404NotUnauthorized() throws Exception {
      mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @WebMvcTest(DevTokenController.class)
  @Import({SecurityConfig.class, Beans.class})
  @EnableConfigurationProperties(ClinicalProperties.class)
  @TestPropertySource(
      properties = {"vitalstream.app-env=dev", "vitalstream.dev-tokens-enabled=false"})
  class FlagFalseInDev {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void noDevBeansAndRouteIs404() throws Exception {
      assertThat(context.getBeanNamesForType(DevTokenController.class)).isEmpty();
      mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isNotFound());
    }
  }

  @Nested
  @WebMvcTest(DevTokenController.class)
  @Import({SecurityConfig.class, Beans.class})
  @EnableConfigurationProperties(ClinicalProperties.class)
  @TestPropertySource(
      properties = {"vitalstream.app-env=prod", "vitalstream.dev-tokens-enabled=true"})
  class OutsideDev {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void theEndpointAndTheHmacIssuerAreNotRegisteredAtAll() {
      assertThat(context.getBeanNamesForType(DevTokenController.class)).isEmpty();
      assertThat(context.getBeanNamesForType(TokenIssuer.class)).isEmpty();
    }

    @Test
    void isNotFoundInProd() throws Exception {
      mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void aMalformedBodyIsAlsoNotFound() throws Exception {
      for (String bad : new String[] {"{not json", "", "[1,2"}) {
        mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isNotFound());
      }
    }
  }

  @Nested
  @WebMvcTest(DevTokenController.class)
  @Import({SecurityConfig.class, Beans.class})
  @EnableConfigurationProperties(ClinicalProperties.class)
  @TestPropertySource(
      properties = {
        "vitalstream.app-env=dev",
        "vitalstream.dev-tokens-enabled=true",
        "vitalstream.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "vitalstream.jwt.issuer=https://idp.example"
      })
  class InJwksMode {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void isNotFoundEvenInDev() throws Exception {
      mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isNotFound());
      mvc.perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content("{bad"))
          .andExpect(status().isNotFound());
    }

    @Test
    void noDevBeansAreRegistered() {
      assertThat(context.getBeanNamesForType(DevTokenController.class)).isEmpty();
      assertThat(context.getBeanNamesForType(TokenIssuer.class)).isEmpty();
    }

    @Test
    void hmacTokensAreNotAcceptedByTheApi() throws Exception {
      mvc.perform(
              get("/api/v1/patients")
                  .header(
                      "Authorization",
                      "Bearer " + TestTokens.valid("nurse.levi", "Nurse Levi", "CLINICIAN")))
          .andExpect(status().isUnauthorized());
    }
  }
}
