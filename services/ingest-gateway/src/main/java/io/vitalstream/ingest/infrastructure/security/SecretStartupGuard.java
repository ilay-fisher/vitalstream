package io.vitalstream.ingest.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start outside local development with weak secrets. A gateway that silently runs with
 * the published dev API key in staging or production would be open to anyone who read the repo, so
 * when {@code APP_ENV} is not {@code dev} every secret must be set, differ from its dev default and
 * be at least 32 characters. In dev the defaults are allowed but a WARN makes that visible. Only
 * variable names are reported, never values.
 */
@Component
public class SecretStartupGuard {

  static final String DEV_API_KEY = "dev-ingest-key";
  static final String DEV_PSEUDONYM_KEY = "dev-only-pseudonym-key";
  static final int MIN_LENGTH = 32;

  private static final Logger log = LoggerFactory.getLogger(SecretStartupGuard.class);

  private final String env;
  private final String apiKey;
  private final String pseudonymKey;
  private final String redisUrl;

  public SecretStartupGuard(
      @Value("${app.env:dev}") String env,
      @Value("${ingest.api-key:}") String apiKey,
      @Value("${ingest.log-pseudonym-key:}") String pseudonymKey,
      @Value("${spring.data.redis.url:}") String redisUrl) {
    this.env = env;
    this.apiKey = apiKey;
    this.pseudonymKey = pseudonymKey;
    this.redisUrl = redisUrl;
  }

  /** Runs at bean initialisation so a bad configuration stops the application from starting. */
  @PostConstruct
  public void validate() {
    if ("dev".equals(env)) {
      if (usesDevSecrets()) {
        log.warn("running with dev secrets (APP_ENV=dev); never expose this instance");
      }
      return;
    }
    List<String> problems = new ArrayList<>();
    check("INGEST_API_KEY", apiKey, DEV_API_KEY, problems);
    check("LOG_PSEUDONYM_KEY", pseudonymKey, DEV_PSEUDONYM_KEY, problems);
    String redisPassword = redisPassword();
    if (redisPassword != null) {
      check("REDIS_URL (password)", redisPassword, null, problems);
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Refusing to start with APP_ENV=" + env + ": " + String.join("; ", problems));
    }
  }

  /** True when a dev default (or an empty value) is in use. */
  public boolean usesDevSecrets() {
    return isBlank(apiKey)
        || DEV_API_KEY.equals(apiKey)
        || isBlank(pseudonymKey)
        || DEV_PSEUDONYM_KEY.equals(pseudonymKey);
  }

  private static void check(String name, String value, String devDefault, List<String> problems) {
    if (isBlank(value)) {
      problems.add(name + " is empty");
    } else if (value.equals(devDefault)) {
      problems.add(name + " equals the dev default");
    } else if (value.length() < MIN_LENGTH) {
      problems.add(name + " is shorter than " + MIN_LENGTH + " characters");
    }
  }

  private String redisPassword() {
    if (isBlank(redisUrl)) {
      return null;
    }
    try {
      String userInfo = URI.create(redisUrl).getUserInfo();
      if (userInfo == null) {
        return null;
      }
      int colon = userInfo.indexOf(':');
      return colon < 0 ? userInfo : userInfo.substring(colon + 1);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
