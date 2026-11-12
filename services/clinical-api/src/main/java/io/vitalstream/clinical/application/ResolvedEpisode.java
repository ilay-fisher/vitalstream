package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.AlertType;
import java.util.UUID;

/** An episode the resolver just closed, with the parent it was grouped under (if any). */
public record ResolvedEpisode(UUID id, AlertType type, UUID parentAlertId) {}
