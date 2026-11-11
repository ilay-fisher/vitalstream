package io.vitalstream.ingest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vitalstream.ingest.infrastructure.security.SecretStartupGuard;
import org.junit.jupiter.api.Test;

class SecretStartupGuardTest {

  private static final String STRONG_1 = "k7Qz9vL2mX4pR8sT1wY6bN3cF5hJ0dGa";
  private static final String STRONG_2 = "Zp3Lx8Vb1Nq6Tr9Wc2Hd5Jf7Gs4Ky0Me";

  private static SecretStartupGuard guard(
      String env, String apiKey, String pseudonymKey, String redisUrl) {
    return new SecretStartupGuard(env, apiKey, pseudonymKey, redisUrl);
  }

  @Test
  void devAllowsDefaultSecrets() {
    assertThatCode(() ->
            guard("dev", "dev-ingest-key", "dev-only-pseudonym-key", "redis://localhost:6379")
                .validate())
        .doesNotThrowAnyException();
  }

  @Test
  void devWarnsAboutDevSecrets() {
    assertThat(
            guard("dev", "dev-ingest-key", "dev-only-pseudonym-key", "redis://localhost:6379")
                .usesDevSecrets())
        .isTrue();
    assertThat(guard("dev", STRONG_1, STRONG_2, "redis://localhost:6379").usesDevSecrets())
        .isFalse();
  }

  @Test
  void nonDevAcceptsStrongDistinctSecrets() {
    assertThatCode(() -> guard("prod", STRONG_1, STRONG_2, "redis://redis:6379").validate())
        .doesNotThrowAnyException();
  }

  @Test
  void nonDevRejectsDevDefaultSecrets() {
    assertThatThrownBy(
            () -> guard("prod", "dev-ingest-key", STRONG_2, "redis://redis:6379").validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INGEST_API_KEY");
    assertThatThrownBy(() ->
            guard("prod", STRONG_1, "dev-only-pseudonym-key", "redis://redis:6379").validate())
        .hasMessageContaining("LOG_PSEUDONYM_KEY");
  }

  @Test
  void nonDevRejectsEmptyAndShortSecrets() {
    assertThatThrownBy(() -> guard("staging", "", STRONG_2, "redis://redis:6379").validate())
        .hasMessageContaining("INGEST_API_KEY");
    assertThatThrownBy(() -> guard("staging", STRONG_1, "short", "redis://redis:6379").validate())
        .hasMessageContaining("LOG_PSEUDONYM_KEY");
    assertThatThrownBy(() -> guard("staging", null, STRONG_2, "redis://redis:6379").validate())
        .hasMessageContaining("INGEST_API_KEY");
  }

  @Test
  void errorMessageNeverContainsTheSecretValues() {
    assertThatThrownBy(() -> guard("prod", "weak-key", STRONG_2, "redis://redis:6379").validate())
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("weak-key"));
  }

  @Test
  void nonDevChecksRedisPasswordWhenOneIsPresent() {
    assertThatThrownBy(
            () -> guard("prod", STRONG_1, STRONG_2, "redis://default:weak@redis:6379").validate())
        .hasMessageContaining("REDIS_URL");
    assertThatCode(() ->
            guard("prod", STRONG_1, STRONG_2, "redis://default:" + STRONG_1 + "@redis:6379")
                .validate())
        .doesNotThrowAnyException();
  }

  @Test
  void reportsEveryProblemAtOnce() {
    assertThatThrownBy(() -> guard("prod", "", "", "redis://redis:6379").validate())
        .hasMessageContaining("INGEST_API_KEY")
        .hasMessageContaining("LOG_PSEUDONYM_KEY");
  }
}
