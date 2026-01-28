-- Seed specialization fields (one-time import).
--
-- Notes:
-- - Input is lightly-structured Markdown; headings and bullets are all treated as "fields" at the same level.
-- - Keeps the original casing (e.g., "IT", "HVAC", "K-12").
-- - Idempotent: safe if rows already exist; also normalizes casing to this canonical list.

CREATE TEMP TABLE IF NOT EXISTS tmp_seed_specialization_fields_raw (
    raw_name TEXT PRIMARY KEY
) ON COMMIT DROP;

WITH lines AS (
    SELECT trim(v) AS line
    FROM regexp_split_to_table($$
## Law

## Nonprofits

## Retail

## Manufacturing

## Agriculture

## Research

## Real Estate

## Religious Services

## Military and Defense

## Consulting

## Technology

- Software Engineer
- Software Developer
- IT
- Network Engineer
- Data
- Product Manager
- Cybersecurity Analyst

## Marketing

- Sales
- Customer Support
- Brand
- Public Relations

## Finance

- Economics
- Accounting
- Auditor
- Financial Advisor

## Healthcare

- Medical
- Nursing
- Physician
- Pharmacy
- Therapy

## Education

- University
- K-12
- Tutor

## Trades

- Electricians
- Plumbers
- HVAC
- Carpenters
- Welders
- Machinist
- Maintenance
- Construction

## Hospitality - Food & Beverage

- Bars
- Servers
- Chefs
- Housekeepers

## Transportation

- Trucking
- Delivery
- Rideshare
- Warehouse
- Pilots
- Flight Attendants

## Media

- Content Creation
- Journalism
- Acting
- Photographer
- Videographer
- Gaming
- Artisans
- Athletics

## Human Resources

- Recruiter

## Government

- Elected Officials
- Sanitation
- Corrections
- Policy
- Utilities
- Libraries
- Museums

## Emergency Services

- Security Guards
- Law Enforcement
- Firefighting

## Public Health

- Epidemiology
- Health Inspector
$$, E'\\n') v
),
items AS (
    SELECT
        CASE
            WHEN line ~ '^##\\s+' THEN regexp_replace(line, '^##\\s+', '')
            WHEN line ~ '^[-*]\\s+' THEN regexp_replace(line, '^[-*]\\s+', '')
            ELSE NULL
        END AS raw_name
    FROM lines
)
INSERT INTO tmp_seed_specialization_fields_raw(raw_name)
SELECT DISTINCT trim(raw_name)
FROM items
WHERE raw_name IS NOT NULL
  AND trim(raw_name) <> '';

-- Normalize casing for any existing rows (e.g., previously inserted in a different case).
UPDATE communities c
SET name = t.raw_name
FROM tmp_seed_specialization_fields_raw t
WHERE c.kind = 'specialization'
  AND c.specialization_type = 'field'
  AND lower(c.name) = lower(t.raw_name)
  AND c.name IS DISTINCT FROM t.raw_name;

-- Insert missing fields.
INSERT INTO communities (kind, specialization_type, name, short_name, description)
SELECT 'specialization', 'field', t.raw_name, NULL, NULL
FROM tmp_seed_specialization_fields_raw t
WHERE NOT EXISTS (
    SELECT 1
    FROM communities c
    WHERE c.kind = 'specialization'
      AND c.specialization_type = 'field'
      AND lower(c.name) = lower(t.raw_name)
);
