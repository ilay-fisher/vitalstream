package io.vitalstream.clinical.infrastructure.config;

import io.vitalstream.clinical.application.SecretGuard;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Runs the {@link SecretGuard} while the context starts, so a weak secret stops the boot. */
@Component
public class SecretStartupGuard {

  private static final Logger log = LoggerFactory.getLogger(SecretStartupGuard.class);

  public SecretStartupGuard(Environment env) {
    SecretGuard.check(
        env.getProperty("APP_ENV", "dev"),
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
                "vitalstream")),
        log::warn);
  }
}
