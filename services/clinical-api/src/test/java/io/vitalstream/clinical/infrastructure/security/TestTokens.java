package io.vitalstream.clinical.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Mints HS256 tokens with full control over every claim, so negative cases can be built. */
public final class TestTokens {

  public static final String SECRET = "dev-clinical-jwt-secret-0123456789abcdef";
  public static final String ISSUER = "vitalstream-dev";

  private TestTokens() {}

  /** A claim set that passes every rule; tests remove or overwrite one entry. */
  public static Map<String, Object> validClaims(Instant now) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", List.of("clinical-api"));
    claims.put("sub", "nurse.levi");
    claims.put("name", "Nurse Levi");
    claims.put("roles", List.of("CLINICIAN"));
    claims.put("iat", now.minusSeconds(5));
    claims.put("exp", now.plusSeconds(600));
    return claims;
  }

  public static String hs256(String secret, Map<String, Object> claims) {
    JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
    claims.forEach(builder::claim);
    NimbusJwtEncoder encoder =
        new NimbusJwtEncoder(
            new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
    return encoder
        .encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), builder.build()))
        .getTokenValue();
  }

  public static String valid(String sub, String name, String... roles) {
    Map<String, Object> claims = validClaims(Instant.now());
    claims.put("sub", sub);
    if (name == null) {
      claims.remove("name");
    } else {
      claims.put("name", name);
    }
    claims.put("roles", List.of(roles));
    return hs256(SECRET, claims);
  }
}
