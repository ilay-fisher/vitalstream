package io.vitalstream.clinical.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretGuardTest {

  private static final String STRONG = "x".repeat(32);

  private final List<String> warnings = new ArrayList<>();

  @Test
  void devAllowsDefaultsAndWarnsExactlyOnce() {
    List<SecretGuard.Secret> secrets =
        List.of(
            new SecretGuard.Secret(
                "LOG_PSEUDONYM_KEY", "dev-only-pseudonym-key", "dev-only-pseudonym-key"),
            new SecretGuard.Secret("POSTGRES_PASSWORD", "", "vitalstream"));

    assertThatCode(() -> SecretGuard.check("dev", secrets, warnings::add))
        .doesNotThrowAnyException();

    assertThat(warnings).containsExactly("running with dev secrets");
  }

  @Test
  void nonDevAcceptsStrongDistinctSecretsWithoutWarning() {
    List<SecretGuard.Secret> secrets =
        List.of(new SecretGuard.Secret("POSTGRES_PASSWORD", STRONG, "vitalstream"));

    SecretGuard.check("prod", secrets, warnings::add);

    assertThat(warnings).isEmpty();
  }

  @Test
  void nonDevRejectsEmptyDefaultAndShortSecretsNamingEachOffender() {
    List<SecretGuard.Secret> secrets =
        List.of(
            new SecretGuard.Secret("LOG_PSEUDONYM_KEY", null, "dev-only-pseudonym-key"),
            new SecretGuard.Secret("POSTGRES_PASSWORD", "vitalstream", "vitalstream"),
            new SecretGuard.Secret("RABBITMQ_PASSWORD", "short", "vitalstream"),
            new SecretGuard.Secret("OK", STRONG, "x"));

    assertThatThrownBy(() -> SecretGuard.check("prod", secrets, warnings::add))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOG_PSEUDONYM_KEY")
        .hasMessageContaining("POSTGRES_PASSWORD")
        .hasMessageContaining("RABBITMQ_PASSWORD")
        .hasMessageNotContaining("OK")
        .hasMessageNotContaining("short");
  }

  @Test
  void strongSecretEqualToItsDefaultIsStillRejected() {
    List<SecretGuard.Secret> secrets = List.of(new SecretGuard.Secret("K", STRONG, STRONG));

    assertThatThrownBy(() -> SecretGuard.check("staging", secrets, warnings::add))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void passwordIsExtractedFromAmqpUri() {
    assertThat(SecretGuard.passwordOf("amqp://user:p%40ss@rabbitmq:5672")).isEqualTo("p@ss");
    assertThat(SecretGuard.passwordOf("amqp://rabbitmq:5672")).isEmpty();
    assertThat(SecretGuard.passwordOf(null)).isEmpty();
  }
}
