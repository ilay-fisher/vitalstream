package io.vitalstream.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.vitalstream.clinical.application.AlertView;
import io.vitalstream.clinical.domain.Severity;
import io.vitalstream.clinical.domain.TestAlerts;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseAlertHubTest {

  private final SseAlertHub hub = new SseAlertHub();

  @Test
  void registerTracksClients() {
    hub.register();
    hub.register();

    assertThat(hub.clientCount()).isEqualTo(2);
  }

  @Test
  void broadcastAndHeartbeatKeepHealthyClients() {
    hub.register();

    hub.broadcast(AlertView.from(TestAlerts.alert(Severity.HIGH)));
    hub.heartbeat();

    assertThat(hub.clientCount()).isEqualTo(1);
  }

  @Test
  void clientsThatCannotBeWrittenToAreDropped() {
    SseEmitter dead = hub.register();
    hub.register();
    dead.complete();

    hub.broadcast(AlertView.from(TestAlerts.alert(Severity.HIGH)));

    assertThat(hub.clientCount()).isEqualTo(1);
  }
}
