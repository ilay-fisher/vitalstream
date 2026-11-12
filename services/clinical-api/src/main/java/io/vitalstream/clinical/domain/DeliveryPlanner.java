package io.vitalstream.clinical.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Chooses which downstream sinks a changed episode must reach. */
public final class DeliveryPlanner {

  private DeliveryPlanner() {}

  /**
   * Only a top-level episode that just opened or escalated may page anyone: updates, children,
   * acknowledgements and resolutions are shown live but stay quiet (CONTRACTS section 3.4). An
   * unlinked episode pages only while still OPEN: an acknowledged one already has an owner.
   * Independent of how recent the episode is.
   */
  private static boolean pagesWhenFresh(Alert episode, EpisodeChange change) {
    boolean owned = change == EpisodeChange.UNLINKED && episode.status() != AlertStatus.OPEN;
    return change.mayNotify() && episode.isTopLevel() && !owned;
  }

  /**
   * An episode whose condition already went quiet must not page: {@code lastSeenAt} older than
   * {@code staleBefore} ({@code now - ALERT_RESOLVE_AFTER}). Exactly at the cutoff is not stale.
   */
  private static boolean isStale(Alert episode, Instant staleBefore) {
    return episode.lastSeenAt().isBefore(staleBefore);
  }

  /**
   * Whether this change pages anyone: it is a paging change (see {@link #pagesWhenFresh}) and the
   * episode is not stale.
   *
   * @param staleBefore {@code now - ALERT_RESOLVE_AFTER}, supplied by the caller so this class
   *     stays free of clocks and configuration
   */
  public static boolean notifies(Alert episode, EpisodeChange change, Instant staleBefore) {
    return pagesWhenFresh(episode, change) && !isStale(episode, staleBefore);
  }

  /** True when the only reason this change does not page is that the episode went stale. */
  public static boolean skippedAsStale(Alert episode, EpisodeChange change, Instant staleBefore) {
    return pagesWhenFresh(episode, change) && isStale(episode, staleBefore);
  }

  /**
   * Every change is indexed so search stays complete; only unsuppressed, non-stale notifying
   * changes notify, and only critical ones escalate.
   */
  public static List<OutboxEventType> plan(
      Alert episode, EpisodeChange change, boolean suppressed, Instant staleBefore) {
    List<OutboxEventType> plan = new ArrayList<>();
    plan.add(OutboxEventType.ALERT_INDEX);
    if (notifies(episode, change, staleBefore) && !suppressed) {
      plan.add(OutboxEventType.ALERT_NOTIFY);
      if (episode.severity().escalates()) {
        plan.add(OutboxEventType.ALERT_ESCALATE);
      }
    }
    return List.copyOf(plan);
  }
}
