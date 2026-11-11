package io.vitalstream.clinical.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Log pseudonym for a patient id (CONTRACTS section 7): the first 12 hex chars of
 * HMAC-SHA256(patientId, LOG_PSEUDONYM_KEY). The patient id is a medical record identifier and must
 * never appear in logs; the same algorithm in every service keeps refs correlatable across logs.
 */
public final class PatientRef {

  private static final String DEFAULT_KEY = "dev-only-pseudonym-key";
  private static final String KEY = resolveKey();

  private PatientRef() {}

  /** Pseudonym using the service-wide key from {@code LOG_PSEUDONYM_KEY}. */
  public static String of(String patientId) {
    return of(patientId, KEY);
  }

  static String of(String patientId, String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(patientId.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String resolveKey() {
    String configured = System.getenv("LOG_PSEUDONYM_KEY");
    return configured == null || configured.isBlank() ? DEFAULT_KEY : configured;
  }
}
