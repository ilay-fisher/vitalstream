package io.vitalstream.clinical.infrastructure.elasticsearch;

import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.application.AlertSearch;
import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.application.OutboxMessage;
import io.vitalstream.clinical.application.OutboxSink;
import io.vitalstream.clinical.application.PageResult;
import io.vitalstream.clinical.domain.OutboxEventType;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Elasticsearch read model (CQRS-lite): the outbox feeds it, search reads from it. Uses the plain
 * REST API through {@link RestClient} to avoid client/server version coupling.
 *
 * <p>Writes use the alert {@code version} as an external document version, so a delayed index of an
 * older state can never overwrite a newer one (e.g. OPEN after ACKNOWLEDGED).
 */
@Component
public class ElasticsearchAlertGateway implements OutboxSink, AlertSearch {

  private static final String MAPPING =
      """
      {"mappings":{"properties":{
        "alertId":{"type":"keyword"},"patientId":{"type":"keyword"},"type":{"type":"keyword"},
        "severity":{"type":"keyword"},"metric":{"type":"keyword"},"value":{"type":"double"},
        "score":{"type":"double"},"message":{"type":"text","analyzer":"english"},
        "windowStart":{"type":"date"},"windowEnd":{"type":"date"},"detectedAt":{"type":"date"},
        "status":{"type":"keyword"},"acknowledgedBy":{"type":"keyword"},
        "acknowledgedAt":{"type":"date"},"acknowledgementNote":{"type":"text"},
        "version":{"type":"long"}}}}
      """;

  private final RestClient client;
  private final JsonMapper mapper;
  private final String index;
  private final AtomicBoolean indexReady = new AtomicBoolean();

  public ElasticsearchAlertGateway(
      RestClient elasticsearchClient, JsonMapper mapper, ClinicalProperties properties) {
    this.client = elasticsearchClient;
    this.mapper = mapper;
    this.index = properties.elasticsearch().index();
  }

  @Override
  public OutboxEventType handles() {
    return OutboxEventType.ALERT_INDEX;
  }

  @Override
  public void deliver(OutboxMessage message) {
    ensureIndex();
    AlertView alert = message.alert();
    try {
      client
          .put()
          .uri(
              "/{index}/_doc/{id}?version={v}&version_type=external_gte",
              index,
              alert.alertId(),
              alert.version())
          .contentType(MediaType.APPLICATION_JSON)
          .body(mapper.writeValueAsString(alert))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.Conflict newerVersionAlreadyIndexed) {
      // a newer state is already indexed: the write is obsolete, not failed
    }
  }

  @Override
  public PageResult<AlertView> search(AlertFilter filter) {
    ensureIndex();
    String response =
        client
            .post()
            .uri("/{index}/_search", index)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(searchBody(filter)))
            .retrieve()
            .body(String.class);
    JsonNode hits = mapper.readTree(response).path("hits");
    List<AlertView> items = new ArrayList<>();
    for (JsonNode hit : hits.path("hits")) {
      items.add(mapper.treeToValue(hit.path("_source"), AlertView.class));
    }
    return new PageResult<>(
        items, filter.page(), filter.size(), hits.path("total").path("value").asLong(0));
  }

  private Map<String, Object> searchBody(AlertFilter filter) {
    List<Object> filters = new ArrayList<>();
    addTerm(filters, "patientId", filter.patientId());
    addTerm(filters, "severity", filter.severity() == null ? null : filter.severity().name());
    addTerm(filters, "status", filter.status() == null ? null : filter.status().name());
    Map<String, Object> text =
        Map.of(
            "simple_query_string",
            Map.of(
                "query", filter.q(),
                "fields", List.of("message^2", "acknowledgementNote"),
                "default_operator", "and"));
    return Map.of(
        "from", filter.offset(),
        "size", filter.size(),
        "track_total_hits", true,
        "sort", List.of(Map.of("detectedAt", "desc")),
        "query", Map.of("bool", Map.of("must", List.of(text), "filter", filters)));
  }

  private static void addTerm(List<Object> filters, String field, String value) {
    if (value != null) {
      filters.add(Map.of("term", Map.of(field, value)));
    }
  }

  private void ensureIndex() {
    if (indexReady.get()) {
      return;
    }
    try {
      client
          .put()
          .uri("/{index}", index)
          .contentType(MediaType.APPLICATION_JSON)
          .body(MAPPING)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.BadRequest e) {
      if (!e.getResponseBodyAsString().contains("resource_already_exists_exception")) {
        throw e;
      }
    }
    indexReady.set(true);
  }
}
