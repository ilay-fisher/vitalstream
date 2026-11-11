package io.vitalstream.clinical.application;

import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

/**
 * Startup guard against shipping local-dev secrets (CONTRACTS section 4.1). Outside {@code dev} the
 * service refuses to start when a secret is empty, still equals its dev default or is shorter than
 * 32 characters; in {@code dev} it only warns once.
 */
public final class SecretGuard {

  static final int MIN_LENGTH = 32;

  /** A named secret and the well-known dev default it must not equal outside dev. */
  public record Secret(String name, String value, String devDefault) {

    boolean weak() {
      return value == null
          || value.isEmpty()
          || value.equals(devDefault)
          || value.length() < MIN_LENGTH;
    }
  }

  private SecretGuard() {}

  /**
   * @throws IllegalStateException outside dev when any secret is weak; the message lists names
   *     only, never values
   */
  public static void check(String appEnv, List<Secret> secrets, Consumer<String> warn) {
    if ("dev".equals(appEnv)) {
      warn.accept("running with dev secrets");
      return;
    }
    List<String> offenders = secrets.stream().filter(Secret::weak).map(Secret::name).toList();
    if (!offenders.isEmpty()) {
      throw new IllegalStateException(
          "APP_ENV="
              + appEnv
              + ": refusing to start, secrets must be set, non-default and at least "
              + MIN_LENGTH
              + " chars: "
              + String.join(", ", offenders));
    }
  }

  /** Password part of a URI such as {@code amqp://user:pass@host:5672}, empty when absent. */
  public static String passwordOf(String uri) {
    if (uri == null) {
      return "";
    }
    String userInfo = URI.create(uri).getUserInfo();
    if (userInfo == null || !userInfo.contains(":")) {
      return "";
    }
    return userInfo.substring(userInfo.indexOf(':') + 1);
  }
}
