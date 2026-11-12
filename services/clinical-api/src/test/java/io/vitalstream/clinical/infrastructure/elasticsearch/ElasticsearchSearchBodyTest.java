package io.vitalstream.clinical.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.clinical.application.AlertFilter;
import io.vitalstream.clinical.domain.AlertStatus;
import io.vitalstream.clinical.domain.AlertType;
import io.vitalstream.clinical.domain.Severity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ElasticsearchSearchBodyTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  private JsonNode body(AlertFilter filter) {
    return mapper.valueToTree(ElasticsearchAlertGateway.searchBody(filter));
  }

  @Test
  void sortsByLastSeenAtDescending() {
    JsonNode body = body(new AlertFilter(null, null, null, null, false, null, "fever", 2, 10));

    assertThat(body.path("sort").get(0).path("lastSeenAt").asString()).isEqualTo("desc");
    assertThat(body.path("from").asInt()).isEqualTo(20);
    assertThat(body.path("size").asInt()).isEqualTo(10);
    assertThat(body.path("query").path("bool").path("filter")).isEmpty();
  }

  @Test
  void repeatableStatusBecomesATermsFilter() {
    JsonNode filter =
        body(new AlertFilter(
                null,
                null,
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED),
                null,
                false,
                null,
                "x",
                0,
                20))
            .path("query")
            .path("bool")
            .path("filter");

    assertThat(filter).hasSize(1);
    assertThat(filter.get(0).path("terms").path("status")).hasSize(2);
  }

  @Test
  void typeSeverityPatientAndParentAreTermFilters() {
    UUID parent = UUID.randomUUID();
    JsonNode filter =
        body(new AlertFilter(
                "P-1", Severity.HIGH, null, AlertType.NEWS2, false, parent, "x", 0, 20))
            .path("query")
            .path("bool")
            .path("filter");

    assertThat(filter).hasSize(4);
    assertThat(filter.toString())
        .contains("\"patientId\":\"P-1\"")
        .contains("\"severity\":\"HIGH\"")
        .contains("\"type\":\"NEWS2\"")
        .contains("\"parentAlertId\":\"" + parent + "\"");
  }

  @Test
  void topLevelExcludesDocumentsWithAParent() {
    JsonNode bool =
        body(new AlertFilter(null, null, null, null, true, null, "x", 0, 20))
            .path("query")
            .path("bool");

    assertThat(bool.path("must_not").get(0).path("exists").path("field").asString())
        .isEqualTo("parentAlertId");
  }
}
