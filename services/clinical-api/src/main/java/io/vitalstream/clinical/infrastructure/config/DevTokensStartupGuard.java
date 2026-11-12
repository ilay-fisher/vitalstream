package io.vitalstream.clinical.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails the boot when {@code CLINICAL_DEV_TOKENS_ENABLED=true} contradicts the environment, so a
 * misconfigured deployment stops instead of silently running without the endpoint. When the
 * endpoint is legitimately on, logs one WARN because anyone who can reach it can mint any role.
 * {@link ConditionalOnDevTokens} keeps the beans out in every other case; this guard only reports
 * the contradictions.
 */
@Component
public class DevTokensStartupGuard {

  private static final Logger log = LoggerFactory.getLogger(DevTokensStartupGuard.class);

  public DevTokensStartupGuard(Environment env) {
    if (!env.getProperty("vitalstream.dev-tokens-enabled", Boolean.class, false)) {
      return;
    }
    if (!"dev".equals(env.getProperty("vitalstream.app-env", "dev"))) {
      throw new IllegalStateException(
          "CLINICAL_DEV_TOKENS_ENABLED=true is only allowed with APP_ENV=dev; "
              + "unset it or run in dev");
    }
    if (!env.getProperty("vitalstream.jwt.jwk-set-uri", "").isBlank()) {
      throw new IllegalStateException(
          "CLINICAL_DEV_TOKENS_ENABLED=true cannot be combined with CLINICAL_JWT_JWK_SET_URI; "
              + "unset one of them");
    }
    log.warn("dev token endpoint enabled: unauthenticated token minting");
  }
}
