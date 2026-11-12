package io.vitalstream.clinical.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Deterministic episode ids (CONTRACTS section 3.4): {@code uuid5(NAMESPACE_URL,
 * "episode|patientId|type|metric|windowStart")} of the episode's first detection. Replays and other
 * replicas re-derive the same id, so reopening is idempotent.
 */
public final class EpisodeIds {

  /** RFC 4122 URL namespace. */
  private static final UUID NAMESPACE_URL = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

  private static final DateTimeFormatter WINDOW_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private EpisodeIds() {}

  /** Id of the episode opened by the detection with the given identity and window start. */
  public static UUID of(String patientId, AlertType type, String metric, Instant windowStart) {
    String name =
        "episode|"
            + patientId
            + "|"
            + type
            + "|"
            + (metric == null ? "" : metric)
            + "|"
            + WINDOW_FORMAT.format(windowStart.truncatedTo(ChronoUnit.SECONDS));
    return uuid5(NAMESPACE_URL, name);
  }

  private static UUID uuid5(UUID namespace, String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(toBytes(namespace));
      byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
      hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
      hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
      long msb = 0;
      long lsb = 0;
      for (int i = 0; i < 8; i++) {
        msb = (msb << 8) | (hash[i] & 0xff);
        lsb = (lsb << 8) | (hash[8 + i] & 0xff);
      }
      return new UUID(msb, lsb);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is required by every JRE", e);
    }
  }

  private static byte[] toBytes(UUID uuid) {
    byte[] bytes = new byte[16];
    long msb = uuid.getMostSignificantBits();
    long lsb = uuid.getLeastSignificantBits();
    for (int i = 0; i < 8; i++) {
      bytes[i] = (byte) (msb >>> (8 * (7 - i)));
      bytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
    }
    return bytes;
  }
}
