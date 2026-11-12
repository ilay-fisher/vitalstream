package io.vitalstream.clinical.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vitalstream.clinical.application.TokenIssuer;
import io.vitalstream.clinical.application.TokenIssuer.IssuedToken;
import io.vitalstream.clinical.domain.Role;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local demo login: hands out HS256 tokens to anyone, by design. That is only acceptable on a
 * developer machine, so it answers 404 unless {@code APP_ENV=dev} and no external identity provider
 * (JWKS) is configured. The check runs before the body is looked at, so outside dev the endpoint is
 * indistinguishable from one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/dev")
@Tag(name = "Dev login")
public class DevTokenController {

  private static final Logger log = LoggerFactory.getLogger(DevTokenController.class);

  /** Body of {@code POST /api/v1/dev/token}. */
  public record DevTokenRequest(String sub, String name, List<String> roles) {}

  private final TokenIssuer issuer;
  private final ClinicalProperties properties;

  public DevTokenController(TokenIssuer issuer, ClinicalProperties properties) {
    this.issuer = issuer;
    this.properties = properties;
  }

  @PostMapping("/token")
  @Operation(summary = "Issue a dev token (APP_ENV=dev and no JWKS only, else 404)")
  public IssuedToken token(@RequestBody(required = false) DevTokenRequest request) {
    if (!properties.devTokensEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    if (request == null || request.sub() == null || request.sub().isBlank()) {
      throw new InvalidRequestException("sub is required");
    }
    List<Role> roles = parseRoles(request.roles());
    IssuedToken issued = issuer.issue(request.sub(), request.name(), roles);
    log.info("Issued dev token for {} with roles {}", request.sub(), roles);
    return issued;
  }

  private static List<Role> parseRoles(List<String> names) {
    if (names == null || names.isEmpty()) {
      throw new InvalidRequestException("roles must not be empty");
    }
    return names.stream()
        .map(name ->
            Role.parse(name)
                .orElseThrow(() -> new InvalidRequestException("unknown role: " + name)))
        .distinct()
        .toList();
  }
}
