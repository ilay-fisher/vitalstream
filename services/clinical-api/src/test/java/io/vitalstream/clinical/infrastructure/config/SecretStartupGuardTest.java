package io.vitalstream.clinical.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** CLINICAL_JWT_SECRET joins the startup secret guard when HS256 mode is used (CONTRACTS 4.2). */
class SecretStartupGuardTest {

  private static final String STRONG = "k".repeat(40);

  private static MockEnvironment strongProd() {
    return new MockEnvironment()
        .withProperty("APP_ENV", "prod")
        .withProperty("LOG_PSEUDONYM_KEY", STRONG)
        .withProperty("spring.datasource.password", STRONG)
        .withProperty("spring.rabbitmq.addresses", "amqp://u:" + STRONG + "@rabbit:5672")
        .withProperty("vitalstream.jwt.secret", STRONG);
  }

  @Test
  void devWarnsOnlyAndStartsWithTheDefaultJwtSecret() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("vitalstream.jwt.secret", "dev-clinical-jwt-secret-0123456789abcdef");

    assertThatCode(() -> new SecretStartupGuard(env)).doesNotThrowAnyException();
  }

  @Test
  void prodStartsWhenEverySecretIsStrong() {
    assertThatCode(() -> new SecretStartupGuard(strongProd())).doesNotThrowAnyException();
  }

  @Test
  void prodRefusesTheDevDefaultJwtSecret() {
    MockEnvironment env =
        strongProd()
            .withProperty("vitalstream.jwt.secret", "dev-clinical-jwt-secret-0123456789abcdef");

    assertThatThrownBy(() -> new SecretStartupGuard(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CLINICAL_JWT_SECRET")
        .hasMessageNotContaining("0123456789abcdef");
  }

  @Test
  void prodRefusesAnEmptyOrShortJwtSecret() {
    for (String weak : new String[] {"", "short"}) {
      MockEnvironment env = strongProd().withProperty("vitalstream.jwt.secret", weak);

      assertThatThrownBy(() -> new SecretStartupGuard(env))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("CLINICAL_JWT_SECRET");
    }
  }

  @Test
  void prodWithJwksDoesNotNeedAnHmacSecret() {
    MockEnvironment env =
        strongProd()
            .withProperty("vitalstream.jwt.secret", "")
            .withProperty("vitalstream.jwt.jwk-set-uri", "https://idp.example/jwks");

    assertThatCode(() -> new SecretStartupGuard(env)).doesNotThrowAnyException();
  }
}
