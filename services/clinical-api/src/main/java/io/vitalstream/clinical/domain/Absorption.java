package io.vitalstream.clinical.domain;

/** Result of an episode absorbing a detection: the new state and what kind of change it was. */
public record Absorption(Alert episode, EpisodeChange change) {}
