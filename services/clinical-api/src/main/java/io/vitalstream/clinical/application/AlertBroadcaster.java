package io.vitalstream.clinical.application;

/** Port: delivers a live alert to the clients connected to this replica. */
public interface AlertBroadcaster {

  void broadcast(AlertView alert);
}
