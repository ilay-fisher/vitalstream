package io.vitalstream.clinical.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vitalstream.clinical.api.DevTokenController;
import io.vitalstream.clinical.application.TokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The dev token endpoint is opt-in (CONTRACTS 4.2): present only with the flag on, {@code
 * APP_ENV=dev} and JWKS off; a flag that contradicts the environment stops the boot.
 */
class DevTokensStartupGuardTest {

  private static final String WARNING = "dev token endpoint enabled: unauthenticated token minting";

  @Configuration(proxyBeanMethods = false)
  static class Beans {
    @Bean
    TokenIssuer tokenIssuer() {
      return mock(TokenIssuer.class);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              Beans.class, DevTokenController.class, DevTokensStartupGuard.class);

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger guardLogger = (Logger) LoggerFactory.getLogger(DevTokensStartupGuard.class);

  @BeforeEach
  void captureLogs() {
    logs.start();
    guardLogger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    guardLogger.detachAppender(logs);
  }

  private long warnings() {
    return logs.list.stream()
        .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().equals(WARNING))
        .count();
  }

  @Test
  void flagMissingInDevRegistersNothing() {
    runner
        .withPropertyValues("vitalstream.app-env=dev")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(DevTokenController.class);
          assertThat(warnings()).isZero();
        });
  }

  @Test
  void flagAndAppEnvBothMissingRegistersNothing() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(DevTokenController.class);
    });
  }

  @Test
  void flagFalseRegistersNothing() {
    runner
        .withPropertyValues("vitalstream.app-env=dev", "vitalstream.dev-tokens-enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(DevTokenController.class));
  }

  @Test
  void flagTrueInDevWithoutJwksRegistersTheControllerAndWarnsOnce() {
    runner
        .withPropertyValues("vitalstream.app-env=dev", "vitalstream.dev-tokens-enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(DevTokenController.class);
          assertThat(warnings()).isEqualTo(1);
        });
  }

  @Test
  void flagTrueOutsideDevFailsStartupWithAClearMessage() {
    runner
        .withPropertyValues("vitalstream.app-env=prod", "vitalstream.dev-tokens-enabled=true")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("CLINICAL_DEV_TOKENS_ENABLED=true")
              .hasMessageContaining("APP_ENV");
        });
  }

  @Test
  void flagTrueWithJwksFailsStartupWithAClearMessage() {
    runner
        .withPropertyValues(
            "vitalstream.app-env=dev",
            "vitalstream.dev-tokens-enabled=true",
            "vitalstream.jwt.jwk-set-uri=https://idp.example/jwks")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("CLINICAL_DEV_TOKENS_ENABLED=true")
              .hasMessageContaining("CLINICAL_JWT_JWK_SET_URI");
        });
  }

  @Test
  void flagFalseWithProdAndJwksStillStarts() {
    runner
        .withPropertyValues(
            "vitalstream.app-env=prod", "vitalstream.jwt.jwk-set-uri=https://idp.example/jwks")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
