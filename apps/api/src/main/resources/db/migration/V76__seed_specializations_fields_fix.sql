-- Fix fields seed migration.
--
-- V75 applied successfully but did not insert because Postgres regex does not treat "\s" as whitespace.
-- This migration re-seeds fields using POSIX whitespace class.

CREATE TEMP TABLE IF NOT EXISTS tmp_seed_specialization_fields_raw_fix (
    raw_name TEXT PRIMARY KEY
) ON COMMIT DROP;

WITH lines AS (
    SELECT btrim(v, E' \\t\\r') AS line
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
            WHEN line ~ '^##[[:space:]]+' THEN regexp_replace(line, '^##[[:space:]]+', '')
            WHEN line ~ '^[-*][[:space:]]+' THEN regexp_replace(line, '^[-*][[:space:]]+', '')
            ELSE NULL
        END AS raw_name
    FROM lines
)
INSERT INTO tmp_seed_specialization_fields_raw_fix(raw_name)
SELECT DISTINCT btrim(raw_name, E' \\t\\r')
FROM items
WHERE raw_name IS NOT NULL
  AND btrim(raw_name, E' \\t\\r') <> '';

-- Normalize casing for any existing rows (e.g., previously inserted in a different case).
UPDATE communities c
SET name = t.raw_name
FROM tmp_seed_specialization_fields_raw_fix t
WHERE c.kind = 'specialization'
  AND c.specialization_type = 'field'
  AND lower(c.name) = lower(t.raw_name)
  AND c.name IS DISTINCT FROM t.raw_name;

-- Insert missing fields.
INSERT INTO communities (kind, specialization_type, name, short_name, description)
SELECT 'specialization', 'field', t.raw_name, NULL, NULL
FROM tmp_seed_specialization_fields_raw_fix t
WHERE NOT EXISTS (
    SELECT 1
    FROM communities c
    WHERE c.kind = 'specialization'
      AND c.specialization_type = 'field'
      AND lower(c.name) = lower(t.raw_name)
);
