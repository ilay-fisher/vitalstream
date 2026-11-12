-- Alert episodes (CONTRACTS sections 3.4 and 5): clinical.alerts holds one row per ongoing
-- condition (patient, type, metric) instead of one row per 1-minute detection.

ALTER TABLE clinical.alerts
    ADD COLUMN first_seen_at     timestamptz,
    ADD COLUMN last_seen_at      timestamptz,
    ADD COLUMN occurrences       integer NOT NULL DEFAULT 1,
    ADD COLUMN resolved_at       timestamptz,
    ADD COLUMN parent_alert_id   uuid REFERENCES clinical.alerts (id),
    ADD COLUMN last_detection_id uuid;

-- Every legacy row was one detection: its window is the episode's first and last sighting and its
-- own id is the id of the detection it holds.
UPDATE clinical.alerts
   SET first_seen_at     = coalesce(window_start, detected_at),
       last_seen_at      = coalesce(window_end, detected_at),
       last_detection_id = id,
       resolved_at       = CASE WHEN status = 'RESOLVED' THEN now() END;

ALTER TABLE clinical.alerts
    ALTER COLUMN first_seen_at SET NOT NULL,
    ALTER COLUMN last_seen_at SET NOT NULL,
    ALTER COLUMN last_detection_id SET NOT NULL;

-- Per key the newest non-resolved row survives as the open episode and counts every row of the key
-- as an occurrence; the older rows are history and resolve now. Doing this before the unique index
-- exists is what lets the index be created at all on a database full of legacy duplicates.
WITH ranked AS (
    SELECT id,
           row_number() OVER w AS rank,
           count(*) OVER (PARTITION BY patient_id, type, coalesce(metric, '')) AS total
      FROM clinical.alerts
     WHERE status <> 'RESOLVED'
    WINDOW w AS (PARTITION BY patient_id, type, coalesce(metric, '')
                 ORDER BY window_start DESC NULLS LAST, detected_at DESC, id DESC)
)
UPDATE clinical.alerts a
   SET occurrences = CASE WHEN r.rank = 1 THEN r.total ELSE 1 END,
       status      = CASE WHEN r.rank = 1 THEN a.status ELSE 'RESOLVED' END,
       resolved_at = CASE WHEN r.rank = 1 THEN NULL ELSE now() END,
       version     = a.version + 1
  FROM ranked r
 WHERE a.id = r.id;

-- At most one non-resolved episode per key; the intake path relies on this under concurrency.
CREATE UNIQUE INDEX alerts_active_episode_uidx
    ON clinical.alerts (patient_id, type, (coalesce(metric, '')))
    WHERE status <> 'RESOLVED';

-- The resolver scans non-resolved rows by silence; lists sort by last sighting.
CREATE INDEX alerts_resolver_idx ON clinical.alerts (status, last_seen_at);
CREATE INDEX alerts_last_seen_idx ON clinical.alerts (last_seen_at DESC, id);
CREATE INDEX alerts_parent_idx ON clinical.alerts (parent_alert_id) WHERE parent_alert_id IS NOT NULL;

-- Elasticsearch still holds the per-detection documents with their old statuses. Re-index every
-- row through the outbox so the search read model converges to the migrated state by itself.
INSERT INTO clinical.outbox (id, aggregate_id, event_type, payload, created_at)
SELECT gen_random_uuid(),
       a.id::text,
       'ALERT_INDEX',
       jsonb_build_object(
           'correlationId', 'migration-v2',
           'alert', jsonb_build_object(
               'alertId', a.id,
               'patientId', a.patient_id,
               'type', a.type,
               'severity', a.severity,
               'metric', a.metric,
               'value', a.value,
               'score', a.score,
               'message', a.message,
               'windowStart', to_char(a.window_start AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'windowEnd', to_char(a.window_end AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'detectedAt', to_char(a.detected_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'status', a.status,
               'acknowledgedBy', a.acknowledged_by,
               'acknowledgedAt', to_char(a.acknowledged_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'acknowledgementNote', a.ack_note,
               'version', a.version,
               'firstSeenAt', to_char(a.first_seen_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'lastSeenAt', to_char(a.last_seen_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'occurrences', a.occurrences,
               'resolvedAt', to_char(a.resolved_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
               'parentAlertId', a.parent_alert_id,
               'childCount', 0,
               'lastDetectionId', a.last_detection_id)),
       now()
  FROM clinical.alerts a;
