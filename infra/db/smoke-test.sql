-- Schema smoke tests for the ledger invariants. Idempotent: everything runs in a
-- transaction that is rolled back, except the "real COMMIT" checks, which use
-- unique keys and only leave behind data that failed to commit (i.e. nothing).
--
-- Deliberately runs with search_path pointed at the *other* tenant to prove that
-- trigger functions are pinned to their own schema.
\set QUIET on
\set ON_ERROR_STOP on
\o /dev/null
SET client_min_messages = notice;
SET search_path = t_globex;

CREATE FUNCTION pg_temp.ok(p_cond BOOLEAN, p_msg TEXT) RETURNS VOID
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT coalesce(p_cond, FALSE) THEN
        RAISE EXCEPTION 'FAIL: %', p_msg;
    END IF;
    RAISE NOTICE 'PASS: %', p_msg;
END;
$$;

-- Runs p_sql (single statement), forces deferred constraints, expects p_state.
CREATE FUNCTION pg_temp.expect_error(p_sql TEXT, p_state TEXT, p_msg TEXT) RETURNS VOID
    LANGUAGE plpgsql AS
$$
BEGIN
    BEGIN
        EXECUTE p_sql;
        SET CONSTRAINTS ALL IMMEDIATE;
    EXCEPTION
        WHEN OTHERS THEN
            SET CONSTRAINTS ALL DEFERRED;
            IF SQLSTATE = p_state THEN
                RAISE NOTICE 'PASS: % [% %]', p_msg, SQLSTATE, SQLERRM;
                RETURN;
            END IF;
            RAISE EXCEPTION 'FAIL: % - expected %, got % (%)', p_msg, p_state, SQLSTATE, SQLERRM;
    END;
    RAISE EXCEPTION 'FAIL: % - expected %, statement succeeded', p_msg, p_state;
END;
$$;

-- ---------------------------------------------------------------------------
-- 1. Placement: tenant objects live in tenant schemas only.
-- ---------------------------------------------------------------------------
SELECT pg_temp.ok(to_regclass('public.ledger_entries') IS NULL AND to_regclass('t_acme.ledger_entries') IS NOT NULL
                      AND to_regclass('t_globex.ledger_entries') IS NOT NULL,
                  'tenant tables exist per schema and not in public');
SELECT pg_temp.ok((SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent
                   JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE c.relname = 'ledger_entries' AND n.nspname = 't_acme') = 38,
                  'ledger_entries has 37 monthly partitions + default');
SELECT pg_temp.ok((SELECT count(*) FROM pg_indexes WHERE schemaname = 't_acme'
                   AND indexname LIKE 'ledger_entries_2%account_id_effective_date_%') = 37,
                  'covering (account_id, effective_date) index propagated to every partition');
SELECT pg_temp.ok(EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'ai' AND indexname = 'vector_store_embedding_hnsw'),
                  'HNSW index on ai.vector_store');

-- ---------------------------------------------------------------------------
-- 2. Fixtures (rolled back at the end)
-- ---------------------------------------------------------------------------
BEGIN;

INSERT INTO t_acme.accounts (code, name, type, normal_balance, currency, category, allow_negative)
VALUES ('1000', 'Operating Cash', 'ASSET', 'D', 'USD', NULL, FALSE),
       ('2000', 'Accounts Payable', 'LIABILITY', 'C', 'USD', NULL, TRUE),
       ('4000', 'Subscription Revenue', 'REVENUE', 'C', 'USD', NULL, TRUE),
       ('6100', 'Cloud Infrastructure', 'EXPENSE', 'D', 'USD', 'CLOUD', TRUE),
       ('1100', 'EUR Cash', 'ASSET', 'D', 'EUR', NULL, TRUE);

INSERT INTO t_globex.accounts (code, name, type, normal_balance, currency)
VALUES ('1000', 'Cash', 'ASSET', 'D', 'USD');

SELECT pg_temp.ok((SELECT count(*) FROM t_acme.account_balances) = (SELECT count(*) FROM t_acme.accounts),
                  'balance rows auto-created per account');

-- ---------------------------------------------------------------------------
-- 3. Balanced posting succeeds and maintains balances, rollups and the chain.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE acct AS SELECT code, id FROM t_acme.accounts;

WITH je AS (
    INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
        VALUES ('smoke-1', current_date, 'Invoice #1001 paid', 'API') RETURNING id, effective_date)
INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
SELECT je.id, v.line_no, je.effective_date, (SELECT id FROM acct WHERE code = v.code), v.dir, v.amount, 'USD'
FROM je, (VALUES (1, '1000', 'D', 1500.00), (2, '4000', 'C', 1500.00)) v(line_no, code, dir, amount);

WITH je AS (
    INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
        VALUES ('smoke-2', current_date, 'AWS bill', 'API') RETURNING id, effective_date)
INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
SELECT je.id, v.line_no, je.effective_date, (SELECT id FROM acct WHERE code = v.code), v.dir, v.amount, 'USD'
FROM je, (VALUES (1, '6100', 'D', 400.25), (2, '1000', 'C', 400.25)) v(line_no, code, dir, amount);

SET CONSTRAINTS ALL IMMEDIATE; -- seal now (what COMMIT would do)
SET CONSTRAINTS ALL DEFERRED;

SELECT pg_temp.ok((SELECT normal_balance_amount FROM t_acme.trial_balance WHERE code = '1000') = 1099.75,
                  'cash balance = 1500.00 - 400.25');
SELECT pg_temp.ok((SELECT sum(debit_total) = sum(credit_total) FROM t_acme.account_balances),
                  'trial balance: total debits = total credits');
SELECT pg_temp.ok((SELECT net_change FROM t_acme.account_period_balances p JOIN acct a ON a.id = p.account_id
                   WHERE a.code = '6100' AND period_start = date_trunc('month', current_date)) = 400.25,
                  'monthly rollup updated in the same transaction');
SELECT pg_temp.ok((SELECT seq FROM t_acme.ledger_chain_head) = 2, 'both entries sealed into the hash chain');
SELECT pg_temp.ok(NOT EXISTS (SELECT 1 FROM t_acme.ledger_verify_chain()), 'hash chain verifies');
SELECT pg_temp.ok((SELECT seq FROM t_globex.ledger_chain_head) = 0 AND
                  (SELECT count(*) FROM t_globex.ledger_entries) = 0,
                  'other tenant untouched despite search_path = t_globex');

-- ---------------------------------------------------------------------------
-- 4. Invariant violations
-- ---------------------------------------------------------------------------
SELECT pg_temp.expect_error(format($q$
    WITH je AS (INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
                VALUES ('bad-unbalanced', current_date, 'x', 'API') RETURNING id, effective_date)
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT je.id, v.n, je.effective_date, v.a, v.d, v.amt, 'USD' FROM je,
      (VALUES (1, %s, 'D', 100.00), (2, %s, 'C', 99.99)) v(n, a, d, amt)$q$,
    (SELECT id FROM acct WHERE code = '6100'), (SELECT id FROM acct WHERE code = '2000')),
    'LG001', 'unbalanced entry rejected at commit');

SELECT pg_temp.expect_error($q$
    INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
    VALUES ('bad-empty', current_date, 'x', 'API')$q$,
    'LG002', 'entry without lines rejected');

SELECT pg_temp.expect_error(format($q$
    WITH je AS (INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
                VALUES ('bad-one-line', current_date, 'x', 'API') RETURNING id, effective_date)
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT je.id, 1, je.effective_date, %s, 'D', 10, 'USD' FROM je$q$, (SELECT id FROM acct WHERE code = '6100')),
    'LG002', 'single-line entry rejected');

SELECT pg_temp.expect_error('UPDATE t_acme.ledger_entries SET amount = amount + 1', 'LG003', 'UPDATE on lines blocked');
SELECT pg_temp.expect_error('DELETE FROM t_acme.journal_entries', 'LG003', 'DELETE on journal entries blocked');
SELECT pg_temp.expect_error('TRUNCATE t_acme.ledger_entries', 'LG003', 'TRUNCATE on lines blocked');

SELECT pg_temp.expect_error(format($q$
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT id, 3, effective_date, %s, 'D', 1, 'USD' FROM t_acme.journal_entries WHERE idempotency_key = 'smoke-1'$q$,
    (SELECT id FROM acct WHERE code = '6100')),
    'LG004', 'line added to sealed entry rejected');

SELECT pg_temp.expect_error(format($q$
    WITH je AS (INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
                VALUES ('bad-unopened', (current_date - INTERVAL '10 years')::date, 'x', 'API') RETURNING id, effective_date)
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT je.id, v.n, je.effective_date, v.a, v.d, 5, 'USD' FROM je, (VALUES (1, %s, 'D'), (2, %s, 'C')) v(n, a, d)$q$,
    (SELECT id FROM acct WHERE code = '6100'), (SELECT id FROM acct WHERE code = '2000')),
    'LG007', 'posting into a never-opened period rejected');

UPDATE t_acme.accounting_periods SET status = 'CLOSED', closed_at = now()
WHERE period_start = (date_trunc('month', current_date) - INTERVAL '1 month')::date;
SELECT pg_temp.expect_error(format($q$
    WITH je AS (INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
                VALUES ('bad-closed', (date_trunc('month', current_date) - INTERVAL '1 day')::date, 'x', 'API')
                RETURNING id, effective_date)
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT je.id, v.n, je.effective_date, v.a, v.d, 5, 'USD' FROM je, (VALUES (1, %s, 'D'), (2, %s, 'C')) v(n, a, d)$q$,
    (SELECT id FROM acct WHERE code = '6100'), (SELECT id FROM acct WHERE code = '2000')),
    'LG005', 'posting into closed period rejected');

SELECT pg_temp.expect_error(format($q$
    WITH je AS (INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
                VALUES ('bad-overdraft', current_date, 'x', 'API') RETURNING id, effective_date)
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT je.id, v.n, je.effective_date, v.a, v.d, 5000, 'USD' FROM je, (VALUES (1, %s, 'D'), (2, %s, 'C')) v(n, a, d)$q$,
    (SELECT id FROM acct WHERE code = '6100'), (SELECT id FROM acct WHERE code = '1000')),
    'LG006', 'overdraft on non-negative cash account rejected');

SELECT pg_temp.expect_error(format($q$
    WITH je AS (INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
                VALUES ('bad-ccy', current_date, 'x', 'API') RETURNING id, effective_date)
    INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
    SELECT je.id, v.n, je.effective_date, v.a, v.d, 5, 'USD' FROM je, (VALUES (1, %s, 'D'), (2, %s, 'C')) v(n, a, d)$q$,
    (SELECT id FROM acct WHERE code = '1100'), (SELECT id FROM acct WHERE code = '4000')),
    '23503', 'line currency must match account currency');

SELECT pg_temp.expect_error($q$
    INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
    VALUES ('smoke-1', current_date, 'replay', 'API')$q$,
    '23505', 'duplicate idempotency key rejected');

SELECT pg_temp.expect_error($q$
    INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
    VALUES ('bad-reversal', current_date, 'x', 'REVERSAL')$q$,
    '23514', 'REVERSAL source requires reverses_entry_id');

-- ---------------------------------------------------------------------------
-- 5. Tamper evidence: bypass triggers as superuser, change an amount, detect it.
-- ---------------------------------------------------------------------------
DO
$$
BEGIN
    BEGIN
        SET LOCAL session_replication_role = replica;
        UPDATE t_acme.ledger_entries SET amount = 1.00 WHERE line_no = 1 AND direction = 'D' AND amount = 1500.00;
        SET LOCAL session_replication_role = origin;
        PERFORM pg_temp.ok((SELECT seq FROM t_acme.ledger_verify_chain()) = 1, 'tampered line detected by hash chain');
        RAISE EXCEPTION USING ERRCODE = 'P0099';
    EXCEPTION
        WHEN SQLSTATE 'P0099' THEN NULL; -- undo the tampering
    END;
END;
$$;
SELECT pg_temp.ok(NOT EXISTS (SELECT 1 FROM t_acme.ledger_verify_chain()), 'chain intact after tamper rollback');

-- ---------------------------------------------------------------------------
-- 6. Vector store tenancy guarantees
-- ---------------------------------------------------------------------------
INSERT INTO ai.vector_store (content, metadata, embedding)
SELECT 'Q3 AWS spend spike', jsonb_build_object('tenant_id', id, 'kind', 'anomaly'),
       array_fill(0.01, ARRAY[1536])::public.vector
FROM public.tenants WHERE slug = 'acme';
SELECT pg_temp.ok((SELECT count(*) FROM ai.vector_store v JOIN public.tenants t ON t.id = v.tenant_id
                   WHERE t.slug = 'acme' AND v.metadata::jsonb @@ format('$.tenant_id == "%s"', t.id)::jsonpath) = 1,
                  'generated tenant_id + Spring AI style jsonpath filter');
SELECT pg_temp.expect_error($q$
    INSERT INTO ai.vector_store (content, metadata, embedding)
    VALUES ('orphan', '{"kind":"x"}', array_fill(0.01, ARRAY[1536])::public.vector)$q$,
    '23502', 'embedding without tenant_id rejected');
SELECT pg_temp.expect_error($q$
    INSERT INTO ai.vector_store (content, metadata, embedding)
    VALUES ('ghost', '{"tenant_id":"00000000-0000-0000-0000-000000000000"}', array_fill(0.01, ARRAY[1536])::public.vector)$q$,
    '23503', 'embedding for unknown tenant rejected');

ROLLBACK;

-- ---------------------------------------------------------------------------
-- 7. Real COMMIT path: an unbalanced entry must fail at COMMIT and leave nothing.
-- ---------------------------------------------------------------------------
INSERT INTO t_acme.accounts (code, name, type, normal_balance, currency)
VALUES ('9998', 'Smoke Debit', 'EXPENSE', 'D', 'USD'), ('9999', 'Smoke Credit', 'LIABILITY', 'C', 'USD')
ON CONFLICT (code) DO NOTHING;

\set ON_ERROR_STOP off
\echo '-- expecting an LG001 error at COMMIT:'
BEGIN;
WITH je AS (
    INSERT INTO t_acme.journal_entries (idempotency_key, effective_date, description, source)
        VALUES ('commit-unbalanced', current_date, 'x', 'API') RETURNING id, effective_date)
INSERT INTO t_acme.ledger_entries (journal_entry_id, line_no, effective_date, account_id, direction, amount, currency)
SELECT je.id, v.n, je.effective_date, (SELECT id FROM t_acme.accounts WHERE code = v.c), v.d, v.amt, 'USD'
FROM je, (VALUES (1, '9998', 'D', 10.00), (2, '9999', 'C', 9.00)) v(n, c, d, amt);
COMMIT;
\set ON_ERROR_STOP on

SELECT pg_temp.ok(NOT EXISTS (SELECT 1 FROM t_acme.journal_entries WHERE idempotency_key = 'commit-unbalanced')
                      AND (SELECT debit_total FROM t_acme.account_balances b JOIN t_acme.accounts a ON a.id = b.account_id
                           WHERE a.code = '9998') = 0,
                  'failed COMMIT left no header, lines or balance changes');
