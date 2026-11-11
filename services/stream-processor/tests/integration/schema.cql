CREATE KEYSPACE IF NOT EXISTS vitalstream
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS vitalstream.vitals_raw (
  patient_id text, day date, observed_at timestamp, metric text, event_id uuid,
  value double, unit text, device_id text,
  PRIMARY KEY ((patient_id, day), observed_at, metric, event_id))
  WITH CLUSTERING ORDER BY (observed_at DESC, metric ASC, event_id ASC)
  AND default_time_to_live = 2592000;

CREATE TABLE IF NOT EXISTS vitalstream.vitals_by_minute (
  patient_id text, metric text, day date, window_start timestamp,
  min double, max double, avg double, count int,
  PRIMARY KEY ((patient_id, metric, day), window_start))
  WITH CLUSTERING ORDER BY (window_start DESC);
