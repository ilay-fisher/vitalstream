package io.vitalstream.clinical.domain;

import java.util.ArrayList;
import java.util.List;

/** Chooses which downstream sinks a changed episode must reach. */
public final class DeliveryPlanner {

  private DeliveryPlanner() {}

  /**
   * Only a top-level episode that just opened or escalated may page anyone: updates, children,
   * acknowledgements and resolutions are shown live but stay quiet (CONTRACTS section 3.4).
   */
  public static boolean notifies(Alert episode, EpisodeChange change) {
    return change.mayNotify() && episode.isTopLevel();
  }

  /**
   * Every change is indexed so search stays complete; only unsuppressed notifying changes notify,
   * and only critical ones escalate.
   */
  public static List<OutboxEventType> plan(
      Alert episode, EpisodeChange change, boolean suppressed) {
    List<OutboxEventType> plan = new ArrayList<>();
    plan.add(OutboxEventType.ALERT_INDEX);
    if (notifies(episode, change) && !suppressed) {
      plan.add(OutboxEventType.ALERT_NOTIFY);
      if (episode.severity().escalates()) {
        plan.add(OutboxEventType.ALERT_ESCALATE);
      }
    }
    return List.copyOf(plan);
  }
}
