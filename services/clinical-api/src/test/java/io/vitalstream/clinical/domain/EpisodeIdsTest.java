package io.vitalstream.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EpisodeIdsTest {

  private static final Instant START = Instant.parse("2026-11-11T20:15:00Z");

  @Test
  void matchesTheUuid5OfTheContractString() {
    // python: uuid.uuid5(NAMESPACE_URL, "episode|P-0001|THRESHOLD|HEART_RATE|2026-11-11T20:15:00Z")
    assertThat(EpisodeIds.of("P-0001", AlertType.THRESHOLD, "HEART_RATE", START))
        .isEqualTo(UUID.fromString("3aa48357-22d4-519f-812f-9b19678d9649"));
  }

  @Test
  void nullMetricIsAnEmptySegment() {
    assertThat(EpisodeIds.of("P-0001", AlertType.NEWS2, null, START))
        .isEqualTo(UUID.fromString("3bf89c5c-7c48-5ec3-99c1-f78a38a3cd58"));
  }

  @Test
  void windowStartIsFormattedWithoutMillis() {
    assertThat(EpisodeIds.of("P-0001", AlertType.NEWS2, null, START.plusMillis(250)))
        .isEqualTo(EpisodeIds.of("P-0001", AlertType.NEWS2, null, START));
  }

  @Test
  void differentWindowsGiveDifferentEpisodes() {
    assertThat(EpisodeIds.of("P-0001", AlertType.NEWS2, null, START.plusSeconds(60)))
        .isNotEqualTo(EpisodeIds.of("P-0001", AlertType.NEWS2, null, START));
  }
}
