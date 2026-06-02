-- Banking demo PostgreSQL schema
-- Executed on every bootstrap-phase2.sh run — drops and recreates all objects
-- so each run starts from a known-clean state with fresh seed data.

-- ── Drop existing objects (dependency order: child tables first) ─────────────
DROP TABLE IF EXISTS ledger_entries CASCADE;
DROP TABLE IF EXISTS transactions    CASCADE;
DROP TABLE IF EXISTS accounts        CASCADE;
DROP SEQUENCE IF EXISTS ledger_entries_seq CASCADE;

-- ── Tables ───────────────────────────────────────────────────────────────────
CREATE TABLE accounts (
  account_id   VARCHAR(20)    PRIMARY KEY,
  balance      NUMERIC(15,2)  NOT NULL DEFAULT 1000000.00,
  version      BIGINT         NOT NULL DEFAULT 0,
  last_updated TIMESTAMPTZ    DEFAULT now()
);

CREATE TABLE transactions (
  transaction_id  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id      VARCHAR(20)    NOT NULL,
  type            VARCHAR(6)     NOT NULL,
  amount          NUMERIC(15,2)  NOT NULL,
  balance_after   NUMERIC(15,2),
  processed_at    TIMESTAMPTZ    DEFAULT now(),
  source_cluster  VARCHAR(10)    NOT NULL
);

CREATE TABLE ledger_entries (
  id              BIGSERIAL      PRIMARY KEY,
  account_id      VARCHAR(20)    NOT NULL,
  running_balance NUMERIC(15,2)  NOT NULL,
  as_of           TIMESTAMPTZ    DEFAULT now(),
  source_cluster  VARCHAR(10)
);

-- Hibernate 6 SequenceStyleGenerator (Quarkus Panache) calls
-- nextval('ledger_entries_SEQ') — PostgreSQL folds that string to lowercase,
-- so the sequence must be created unquoted (stored as ledger_entries_seq).
CREATE SEQUENCE ledger_entries_seq START 1 INCREMENT BY 50;

-- ── Seed data — 100 test accounts ────────────────────────────────────────────
INSERT INTO accounts (account_id, balance)
SELECT 'ACC' || LPAD(i::text, 5, '0'), 1000000.00
FROM generate_series(1, 100) AS i;

SELECT 'Schema OK: accounts=' || (SELECT COUNT(*) FROM accounts)::text AS rows;
