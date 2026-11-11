package io.vitalstream.clinical.application;

import io.vitalstream.clinical.domain.AlertNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read side for alerts: Postgres for structured filters, Elasticsearch when {@code q} is set. */
@Service
public class AlertQueryService {

  private final AlertRepository alerts;
  private final AlertSearch search;

  public AlertQueryService(AlertRepository alerts, AlertSearch search) {
    this.alerts = alerts;
    this.search = search;
  }

  public PageResult<AlertView> list(AlertFilter filter) {
    return filter.hasTextQuery() ? search.search(filter) : alerts.search(filter);
  }

  public AlertView get(UUID id) {
    return alerts
        .findById(id)
        .map(AlertView::from)
        .orElseThrow(() -> new AlertNotFoundException(id));
  }
}
