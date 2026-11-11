-- Idempotent bootstrap (CONTRACTS section 5). Component migrations (Flyway/Alembic) own everything
-- else; foundation only guarantees the extension, the two schemas and the seeded clinical.patients.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS clinical;
CREATE SCHEMA IF NOT EXISTS copilot;

CREATE TABLE IF NOT EXISTS clinical.patients (
    id         text PRIMARY KEY,
    name       text NOT NULL,
    bed        text NOT NULL,
    ward       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO clinical.patients (id, name, bed, ward)
SELECT 'P-' || lpad(i::text, 4, '0'),
       (ARRAY['Avi Cohen','Dana Levi','Yossi Mizrahi','Noa Peretz','Eli Biton',
              'Maya Friedman','Omer Katz','Tamar Azulay','Ron Dahan','Shira Amar',
              'Lior Golan','Noam Segal','Yael Hadad','Itai Shalom','Michal Ben-David',
              'Gil Navon','Rina Ohana','Amit Stern','Hila Malka','Daniel Weiss'])[i],
       'B-' || lpad(((i - 1) % 10 + 1)::text, 2, '0'),
       CASE WHEN i <= 10 THEN 'ICU' ELSE 'CARDIOLOGY' END
FROM generate_series(1, 20) AS i
ON CONFLICT (id) DO NOTHING;
