-- Mirrors infra/postgres/init.sql (NOT NULL columns, seeded rows): schema + patients seed exist BEFORE clinical-api's Flyway runs.
CREATE SCHEMA IF NOT EXISTS clinical;
CREATE TABLE IF NOT EXISTS clinical.patients (
    id text PRIMARY KEY,
    name text NOT NULL,
    bed text NOT NULL,
    ward text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO clinical.patients (id, name, bed, ward) VALUES
    ('P-0001', 'Dana Levi', 'ICU-01', 'ICU'),
    ('P-0002', 'Omer Katz', 'ICU-02', 'ICU'),
    ('P-0003', 'Noa Peretz', 'ICU-03', 'ICU')
ON CONFLICT DO NOTHING;
