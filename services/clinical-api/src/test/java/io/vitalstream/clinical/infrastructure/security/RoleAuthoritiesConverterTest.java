package io.vitalstream.clinical.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleAuthoritiesConverterTest {

  private final RoleAuthoritiesConverter converter = new RoleAuthoritiesConverter();

  private List<String> names(Map<String, Object> claims) {
    Jwt.Builder builder =
        Jwt.withTokenValue("t")
            .header("alg", "HS256")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    claims.forEach(builder::claim);
    return converter.convert(builder.build()).stream().map(GrantedAuthority::getAuthority).toList();
  }

  @Test
  void mapsKnownRolesToRolePrefixedAuthorities() {
    assertThat(names(Map.of("sub", "x", "roles", List.of("CLINICIAN", "VIEWER", "SERVICE"))))
        .containsExactlyInAnyOrder("ROLE_CLINICIAN", "ROLE_VIEWER", "ROLE_SERVICE");
  }

  @Test
  void ignoresUnknownRolesAndWrongCasing() {
    assertThat(names(Map.of("sub", "x", "roles", List.of("ADMIN", "clinician", "VIEWER"))))
        .containsExactly("ROLE_VIEWER");
  }

  @Test
  void missingOrMalformedRolesClaimGivesNoAuthorities() {
    assertThat(names(Map.of("sub", "x"))).isEmpty();
    assertThat(names(Map.of("sub", "x", "roles", "CLINICIAN"))).isEmpty();
    assertThat(names(Map.of("sub", "x", "roles", List.of(1, 2)))).isEmpty();
  }
}
