package io.vitalstream.clinical.infrastructure.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.vitalstream.clinical.application.TokenIssuer;
import io.vitalstream.clinical.domain.Role;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs HS256 tokens with the same secret the resource server verifies with. It exists for the
 * local demo login; the HMAC key is only touched on the first {@link #issue}, so JWKS or prod
 * deployments with a blank secret never build it.
 */
public class NimbusTokenIssuer implements TokenIssuer {

  /** Lifetime of a dev token: one working day, so a demo does not log out mid-shift. */
  public static final Duration TTL = Duration.ofHours(8);

  private final ClinicalProperties.Jwt settings;
  private final Clock clock;
  private volatile JwtEncoder encoder;

  public NimbusTokenIssuer(ClinicalProperties.Jwt settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public IssuedToken issue(String sub, String name, List<Role> roles) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = now.plus(TTL);
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(settings.issuer())
            .audience(List.of(ClinicalJwtDecoders.AUDIENCE))
            .subject(sub)
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(RoleAuthoritiesConverter.CLAIM, roles.stream().map(Role::name).toList());
    if (name != null && !name.isBlank()) {
      claims.claim("name", name);
    }
    String token =
        encoder()
            .encode(
                JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
            .getTokenValue();
    return new IssuedToken(token, expiresAt);
  }

  private JwtEncoder encoder() {
    if (encoder == null) {
      encoder =
          new NimbusJwtEncoder(
              new ImmutableSecret<>(
                  new SecretKeySpec(
                      settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
    }
    return encoder;
  }
}
