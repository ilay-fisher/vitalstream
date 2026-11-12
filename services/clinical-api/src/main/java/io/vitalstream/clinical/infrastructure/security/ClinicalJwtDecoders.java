package io.vitalstream.clinical.infrastructure.security;

import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the token verifier of CONTRACTS 4.2: RS256 against a JWKS when one is configured, HS256
 * with the shared secret otherwise. Both modes enforce issuer, audience, expiry (30 s skew) and
 * subject, and each accepts exactly one algorithm so a token cannot downgrade the check.
 */
public final class ClinicalJwtDecoders {

  /** Audience every token must name. */
  public static final String AUDIENCE = "clinical-api";

  /** Tolerated clock difference between the issuer and this service. */
  public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

  static final int MIN_SECRET_BYTES = 32;

  private ClinicalJwtDecoders() {}

  /**
   * @throws IllegalStateException in HS256 mode when the secret is shorter than 32 bytes
   */
  public static JwtDecoder create(ClinicalProperties.Jwt settings) {
    NimbusJwtDecoder decoder =
        settings.jwksMode()
            ? NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build()
            : NimbusJwtDecoder.withSecretKey(hmacKey(settings.secret()))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    decoder.setJwtValidator(validator(settings.issuer()));
    return decoder;
  }

  static OAuth2TokenValidator<Jwt> validator(String issuer) {
    return new DelegatingOAuth2TokenValidator<>(
        new JwtIssuerValidator(issuer),
        new JwtTimestampValidator(CLOCK_SKEW),
        new JwtClaimValidator<Instant>("exp", exp -> exp != null),
        new JwtClaimValidator<String>("sub", sub -> sub != null && !sub.isBlank()),
        new JwtClaimValidator<Collection<String>>(
            "aud", aud -> aud != null && List.copyOf(aud).contains(AUDIENCE)));
  }

  private static SecretKeySpec hmacKey(String secret) {
    byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "CLINICAL_JWT_SECRET must be at least "
              + MIN_SECRET_BYTES
              + " bytes for HS256 (or set CLINICAL_JWT_JWK_SET_URI)");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }
}
