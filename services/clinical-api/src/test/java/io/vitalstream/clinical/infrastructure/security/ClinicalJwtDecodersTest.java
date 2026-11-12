package io.vitalstream.clinical.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class ClinicalJwtDecodersTest {

  private final JwtDecoder decoder =
      ClinicalJwtDecoders.create(
          new ClinicalProperties.Jwt(TestTokens.SECRET, "vitalstream-dev", ""));

  private String token(Consumer<Map<String, Object>> edit) {
    Map<String, Object> claims = TestTokens.validClaims(Instant.now());
    edit.accept(claims);
    return TestTokens.hs256(TestTokens.SECRET, claims);
  }

  @Test
  void acceptsAValidToken() {
    Jwt jwt = decoder.decode(token(c -> {}));

    assertThat(jwt.getSubject()).isEqualTo("nurse.levi");
    assertThat(jwt.getClaimAsString("name")).isEqualTo("Nurse Levi");
  }

  @Test
  void rejectsWrongIssuer() {
    assertThatThrownBy(() -> decoder.decode(token(c -> c.put("iss", "someone-else"))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsMissingIssuer() {
    assertThatThrownBy(() -> decoder.decode(token(c -> c.remove("iss"))))
        .isInstanceOf(JwtException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"copilot", "other-api", ""})
  void rejectsAudienceWithoutClinicalApi(String audience) {
    assertThatThrownBy(() -> decoder.decode(token(c -> c.put("aud", List.of(audience)))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsMissingAudience() {
    assertThatThrownBy(() -> decoder.decode(token(c -> c.remove("aud"))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void acceptsAudienceListThatContainsClinicalApi() {
    assertThat(decoder.decode(token(c -> c.put("aud", List.of("other", "clinical-api")))))
        .isNotNull();
  }

  @Test
  void rejectsExpiredTokenBeyondTheThirtySecondSkew() {
    Instant now = Instant.now();
    String expired =
        token(
            c -> {
              c.put("iat", now.minusSeconds(600));
              c.put("exp", now.minusSeconds(60));
            });

    assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
  }

  @Test
  void toleratesTenSecondsOfClockSkew() {
    Instant now = Instant.now();
    String justExpired =
        token(
            c -> {
              c.put("iat", now.minusSeconds(600));
              c.put("exp", now.minusSeconds(10));
            });

    assertThat(decoder.decode(justExpired)).isNotNull();
  }

  @Test
  void rejectsMissingExp() {
    assertThatThrownBy(() -> decoder.decode(token(c -> c.remove("exp"))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsMissingOrBlankSub() {
    assertThatThrownBy(() -> decoder.decode(token(c -> c.remove("sub"))))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(() -> decoder.decode(token(c -> c.put("sub", " "))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsTokenSignedWithAnotherSecret() {
    String forged =
        TestTokens.hs256(
            "another-secret-another-secret-another-secret", TestTokens.validClaims(Instant.now()));

    assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsGarbage() {
    assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "too-short"})
  void refusesToStartWithAWeakHmacSecret(String secret) {
    ClinicalProperties.Jwt weak = new ClinicalProperties.Jwt(secret, "vitalstream-dev", "");

    assertThatThrownBy(() -> ClinicalJwtDecoders.create(weak))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CLINICAL_JWT_SECRET");
  }

  @Test
  void jwksModeValidatesRs256TokensAndIgnoresTheHmacSecret() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/jwks",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, jwks.length);
          exchange.getResponseBody().write(jwks);
          exchange.close();
        });
    server.start();
    try {
      String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
      JwtDecoder jwksDecoder =
          ClinicalJwtDecoders.create(new ClinicalProperties.Jwt("", "https://idp.example", uri));

      String good = signed(key, "https://idp.example");
      String wrongIssuer = signed(key, "vitalstream-dev");
      String hmac = TestTokens.valid("nurse.levi", "Nurse Levi", "CLINICIAN");

      assertThat(jwksDecoder.decode(good).getSubject()).isEqualTo("nurse.levi");
      assertThatThrownBy(() -> jwksDecoder.decode(wrongIssuer)).isInstanceOf(JwtException.class);
      assertThatThrownBy(() -> jwksDecoder.decode(hmac)).isInstanceOf(JwtException.class);
    } finally {
      server.stop(0);
    }
  }

  private static String signed(RSAKey key, String issuer) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience("clinical-api")
                .subject("nurse.levi")
                .claim("roles", List.of("CLINICIAN"))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
