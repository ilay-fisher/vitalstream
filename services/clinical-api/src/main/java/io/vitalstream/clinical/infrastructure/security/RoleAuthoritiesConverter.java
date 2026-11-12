package io.vitalstream.clinical.infrastructure.security;

import io.vitalstream.clinical.domain.Role;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns the {@code roles} claim into {@code ROLE_*} authorities. Only the roles this service knows
 * are mapped; a missing or malformed claim yields no authority, which the access rules then deny.
 */
public class RoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  public static final String CLAIM = "roles";

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    if (!(jwt.getClaim(CLAIM) instanceof Collection<?> claimed)) {
      return List.of();
    }
    return claimed.stream()
        .map(Role::parse)
        .flatMap(java.util.Optional::stream)
        .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
        .distinct()
        .toList();
  }
}
