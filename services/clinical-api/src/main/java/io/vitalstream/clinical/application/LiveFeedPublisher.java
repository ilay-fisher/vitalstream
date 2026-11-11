package io.vitalstream.clinical.application;

/** Port: broadcast of alert changes to every clinical-api replica (Redis pub/sub). */
public interface LiveFeedPublisher {

  void publish(AlertView alert);
}
