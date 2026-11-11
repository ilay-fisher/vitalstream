package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.Severity;

/**
 * Criteria for listing alerts. {@code q} switches the backing store from Postgres to Elasticsearch
 * because only the index can do full-text relevance search.
 */
public record AlertFilter(
    String patientId, Severity severity, AlertStatus status, String q, int page, int size) {

  public boolean hasTextQuery() {
    return q != null && !q.isBlank();
  }

  public int offset() {
    return page * size;
  }
}
