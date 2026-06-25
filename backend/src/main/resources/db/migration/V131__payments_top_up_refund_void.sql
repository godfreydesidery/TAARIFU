-- =============================================================================
-- V131__payments_top_up_refund_void.sql  —  Phase-2 Wave-2: REFUND/VOID support
--   for the mobile-money TOP-UP lifecycle (payments module, ADR-0015 addendum,
--   "Revisit trigger (b) refunds/chargebacks"; PRD §23.5, §18, D18).
--   Flyway block V130–V139 reserved for the payments module (ADR-0015 §1).
--
-- Responsibility: extend top_up so a SETTLED top-up can be reversed (refund) and
-- an UN-SETTLED attempt can be cancelled (void), each idempotently and audited:
--   * widen the status state machine: INITIATED/PENDING → VOIDED (admin cancel of
--     an un-settled attempt, no credit to reverse); SUCCEEDED → REFUNDED (reverse
--     the convenience-token credit). FAILED/VOIDED/REFUNDED are terminal.
--   * add reversal_event_id — the idempotency key under which the wallet REVERSAL
--     was posted, so a retried refund debits the wallet EXACTLY ONCE (mirrors
--     credit_event_id; PRD §23.5 anti-double-credit, applied in reverse).
--   * add reversal_reason — a REDACTED machine reason for the void/refund (no PII,
--     no provider body; PRD §18).
-- top_up stays a BaseEntity subclass and MUST match the JPA entity for
-- ddl-auto=validate (ARCHITECTURE.md §4.1). Forward-only, with SQL comments.
--
-- DESIGN NOTES (the "why", per CLAUDE.md §8):
--   * 🔒 FENCE (D18): a refund reverses ONLY the convenience-token credit a top-up
--     produced. It never touches a signature/rating/poll outcome/role/routing/SLA/
--     verification status. The reversal is posted through the tokens module's
--     published api port (a REFUND-type ledger entry) — payments never writes
--     tokens' tables (ADR-0013).
--   * VOID vs REFUND are distinct by design: a VOID has NO wallet effect (the
--     un-settled attempt was never credited), so it carries no reversal_event_id;
--     a REFUND debits the wallet and so carries one. The CHECK constraint binds
--     reversal_event_id to the REFUNDED status to make an inconsistent row
--     impossible at the database (a void can never masquerade as a credited-then-
--     reversed row, and vice-versa).
--   * The status CHECK is widened (drop + re-add) to admit the two new terminal
--     states. Existing rows keep their value — no data migration needed.
-- =============================================================================


-- The idempotency key under which the wallet REVERSAL (refund debit) was posted;
-- set once, when the refund is applied. NULL for every non-refunded row.
ALTER TABLE top_up
    ADD COLUMN reversal_event_id UUID;

-- Redacted machine reason for a VOID/REFUND (e.g. ADMIN_CANCELLED, DUPLICATE_CHARGE);
-- no PII, no provider body (PRD §18). NULL unless the top-up was voided/refunded.
ALTER TABLE top_up
    ADD COLUMN reversal_reason VARCHAR(256);

-- Widen the status state machine to admit VOIDED (un-settled cancel) + REFUNDED
-- (settled reversal). Drop + re-add so the constraint name and definition stay
-- explicit and forward-only.
ALTER TABLE top_up
    DROP CONSTRAINT ck_top_up_status;
ALTER TABLE top_up
    ADD CONSTRAINT ck_top_up_status
    CHECK (status IN ('INITIATED','PENDING','SUCCEEDED','FAILED','VOIDED','REFUNDED'));

-- Bind reversal_event_id to the REFUNDED status: a REFUNDED row MUST carry a
-- reversal key (the wallet was debited), and a non-REFUNDED row MUST NOT (no
-- reversal happened). Makes a credited-then-reversed row distinguishable from a
-- never-credited void at the database — the refund idempotency invariant cannot
-- be violated by a stray write.
ALTER TABLE top_up
    ADD CONSTRAINT ck_top_up_reversal_event_id
    CHECK ((status = 'REFUNDED' AND reversal_event_id IS NOT NULL)
        OR (status <> 'REFUNDED' AND reversal_event_id IS NULL));

COMMENT ON COLUMN top_up.reversal_event_id IS 'Idempotency key under which the wallet REVERSAL (refund debit) was posted; makes a retried refund debit exactly once (PRD §23.5, in reverse). Present iff status=REFUNDED (ck_top_up_reversal_event_id).';
COMMENT ON COLUMN top_up.reversal_reason   IS 'Redacted machine reason on VOID/REFUND — no PII, no provider body (PRD §18).';
