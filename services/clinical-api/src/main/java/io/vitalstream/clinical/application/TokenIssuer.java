package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.Role;
import java.time.Instant;
import java.util.List;

/** Port for minting access tokens; only used by the dev login, never in production. */
public interface TokenIssuer {

  /** A signed token and the instant it stops being valid. */
  record IssuedToken(String token, Instant expiresAt) {}

  IssuedToken issue(String sub, String name, List<Role> roles);
}
