-- A notification skipped as stale is owed, not dropped (CONTRACTS section 3.4, Staleness): the flag
-- makes the next fresh update of the still-open episode page once, then clears.
ALTER TABLE clinical.alerts
    ADD COLUMN notify_pending boolean NOT NULL DEFAULT false;
