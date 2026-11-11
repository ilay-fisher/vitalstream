package io.vitalstream.clinical.domain;

import java.util.ArrayList;
import java.util.List;

/** Chooses which downstream sinks a newly stored alert must reach. */
public final class DeliveryPlanner {

  private DeliveryPlanner() {}

  /**
   * Every alert is indexed so search stays complete; only unsuppressed alerts notify, and only
   * critical ones escalate.
   */
  public static List<OutboxEventType> plan(Alert alert, boolean suppressed) {
    List<OutboxEventType> plan = new ArrayList<>();
    plan.add(OutboxEventType.ALERT_INDEX);
    if (!suppressed) {
      plan.add(OutboxEventType.ALERT_NOTIFY);
      if (alert.severity().escalates()) {
        plan.add(OutboxEventType.ALERT_ESCALATE);
      }
    }
    return List.copyOf(plan);
  }
}
