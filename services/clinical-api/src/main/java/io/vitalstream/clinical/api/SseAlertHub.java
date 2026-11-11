package io.vitalstream.clinical.api;

import io.vitalstream.clinical.application.AlertBroadcaster;
import io.vitalstream.clinical.application.AlertView;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of the SSE clients connected to <em>this</em> replica. Alerts reach it from the Redis
 * pub/sub subscriber, never directly from the Kafka consumer, so every replica serves every client
 * regardless of which replica consumed the alert.
 */
@Component
public class SseAlertHub implements AlertBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(SseAlertHub.class);
  private static final long TIMEOUT_MS = 30 * 60 * 1000L;

  private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

  /** Opens a new stream; the client is dropped on completion, timeout or error. */
  public SseEmitter register() {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    clients.add(emitter);
    emitter.onCompletion(() -> remove(emitter));
    emitter.onTimeout(() -> remove(emitter));
    emitter.onError(e -> remove(emitter));
    sendQuietly(emitter, SseEmitter.event().comment("connected"));
    return emitter;
  }

  @Override
  public void broadcast(AlertView alert) {
    SseEmitter.SseEventBuilder event =
        SseEmitter.event().id(alert.alertId() + ":" + alert.version()).name("alert").data(alert);
    clients.forEach(client -> sendQuietly(client, event));
  }

  /** Comment frames keep idle connections alive through proxies and detect dead clients. */
  @Scheduled(fixedDelayString = "${vitalstream.sse.heartbeat-ms:15000}")
  void heartbeat() {
    clients.forEach(client -> sendQuietly(client, SseEmitter.event().comment("keep-alive")));
  }

  public int clientCount() {
    return clients.size();
  }

  private void sendQuietly(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException e) {
      log.debug("Dropping SSE client: {}", e.toString());
      remove(emitter);
    }
  }

  private void remove(SseEmitter emitter) {
    clients.remove(emitter);
  }
}
