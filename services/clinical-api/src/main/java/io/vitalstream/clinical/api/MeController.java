package io.vitalstream.clinical.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.clinical.application.AckActor;
import io.vitalstream.clinical.domain.Role;
import io.vitalstream.clinical.infrastructure.security.RoleAuthoritiesConverter;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the bearer token says the caller is. The dashboard shows it and the copilot uses it to
 * validate a user's token before doing any work for that user.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Identity")
public class MeController {

  /** The caller's identity: {@code name} falls back to {@code sub}; unknown roles are dropped. */
  public record Me(String sub, String name, List<String> roles) {}

  @GetMapping
  @Operation(summary = "Identity and roles of the caller's token")
  public Me me(@AuthenticationPrincipal Jwt token) {
    AckActor actor = AckActor.from(token.getSubject(), token.getClaimAsString("name"));
    List<String> claimed = token.getClaimAsStringList(RoleAuthoritiesConverter.CLAIM);
    List<String> roles =
        claimed == null
            ? List.of()
            : claimed.stream()
                .flatMap(r -> Role.parse(r).stream())
                .map(Role::name)
                .distinct()
                .toList();
    return new Me(actor.sub(), actor.displayName(), roles);
  }
}
