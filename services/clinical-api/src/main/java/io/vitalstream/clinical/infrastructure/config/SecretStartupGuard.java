package io.vitalstream.clinical.infrastructure.config;

import io.vitalstream.clinical.application.SecretGuard;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link SecretGuard} while the context starts, so a weak secret stops the boot. The JWT
 * HMAC secret is only a secret when HS256 mode is active; with a JWKS URI the keys live at the
 * identity provider.
 */
@Component
public class SecretStartupGuard {

  private static final Logger log = LoggerFactory.getLogger(SecretStartupGuard.class);

  public SecretStartupGuard(Environment env) {
    List<SecretGuard.Secret> secrets = new ArrayList<>();
    secrets.addAll(
        List.of(
            new SecretGuard.Secret(
                "LOG_PSEUDONYM_KEY",
                env.getProperty("LOG_PSEUDONYM_KEY"),
                "dev-only-pseudonym-key"),
            new SecretGuard.Secret(
                "POSTGRES_PASSWORD", env.getProperty("spring.datasource.password"), "vitalstream"),
            new SecretGuard.Secret(
                "RABBITMQ_PASSWORD",
                SecretGuard.passwordOf(env.getProperty("spring.rabbitmq.addresses")),
                "vitalstream")));
    if (!hasText(env.getProperty("vitalstream.jwt.jwk-set-uri"))) {
      secrets.add(
          new SecretGuard.Secret(
              "CLINICAL_JWT_SECRET",
              env.getProperty("vitalstream.jwt.secret"),
              "dev-clinical-jwt-secret-0123456789abcdef"));
    }
    SecretGuard.check(env.getProperty("APP_ENV", "dev"), secrets, log::warn);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
