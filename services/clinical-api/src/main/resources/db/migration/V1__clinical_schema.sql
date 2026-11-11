-- Idempotent with the infra init script, which already creates the schema and
-- clinical.patients (+ 20 seeded patients). IF NOT EXISTS keeps both orders safe.
CREATE SCHEMA IF NOT EXISTS clinical;

CREATE TABLE IF NOT EXISTS clinical.patients (
    id         text PRIMARY KEY,
    name       text,
    bed        text,
    ward       text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS clinical.alerts (
    id              uuid PRIMARY KEY,
    patient_id      text        NOT NULL,
    type            text        NOT NULL CHECK (type IN ('THRESHOLD', 'TREND_ZSCORE', 'NEWS2', 'ML_ANOMALY')),
    severity        text        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    metric          text,
    value           double precision,
    score           double precision,
    message         text,
    window_start    timestamptz,
    window_end      timestamptz,
    detected_at     timestamptz NOT NULL,
    status          text        NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    acknowledged_by text,
    acknowledged_at timestamptz,
    -- additive to CONTRACTS section 5: the ack endpoint accepts a free-text note
    ack_note        text,
    version         bigint      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS alerts_patient_detected_idx ON clinical.alerts (patient_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS alerts_detected_idx ON clinical.alerts (detected_at DESC);
CREATE INDEX IF NOT EXISTS alerts_open_idx ON clinical.alerts (patient_id) WHERE status = 'OPEN';

CREATE TABLE IF NOT EXISTS clinical.outbox (
    id           uuid PRIMARY KEY,
    aggregate_id text        NOT NULL,
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

-- The relay only ever scans unpublished rows; keep that set cheap to find.
CREATE INDEX IF NOT EXISTS outbox_unpublished_idx ON clinical.outbox (created_at, id) WHERE published_at IS NULL;
