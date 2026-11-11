package io.vitalstream.ingest.infrastructure.logging;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a patient id (a medical record identifier) into a short keyed pseudonym for logs: the first
 * 12 hex characters of HMAC-SHA256(patientId, key). Every VitalStream service uses the same
 * algorithm so one patient's log lines correlate across services without exposing the id.
 */
public class PatientPseudonymizer {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int HEX_CHARS = 12;

  private final SecretKeySpec key;

  public PatientPseudonymizer(String secret) {
    this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  /** Returns the log-safe reference for the patient, or {@code none} when there is no patient. */
  public String ref(String patientId) {
    if (patientId == null) {
      return "none";
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      byte[] digest = mac.doFinal(patientId.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
