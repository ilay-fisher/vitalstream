package io.vitalstream.clinical.domain;

import java.util.Optional;

/**
 * Roles carried in the {@code roles} claim of an access token (CONTRACTS 4.2). Unknown role names
 * grant nothing, so a provider can add its own without widening access here.
 */
public enum Role {
  /** Reads everything and may acknowledge alerts. */
  CLINICIAN,
  /** Read only. */
  VIEWER,
  /** Machine clients such as the copilot: read only. */
  SERVICE;

  /** Exact, case-sensitive match; anything else is not a role of this service. */
  public static Optional<Role> parse(Object value) {
    if (value instanceof String name) {
      for (Role role : values()) {
        if (role.name().equals(name)) {
          return Optional.of(role);
        }
      }
    }
    return Optional.empty();
  }

  /** Spring Security authority name for this role. */
  public String authority() {
    return "ROLE_" + name();
  }
}
