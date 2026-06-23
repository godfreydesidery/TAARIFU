-- =============================================================================
-- V7__identity_mfa_replay_and_electoral_anchor.sql
--   Two security must-fixes from docs/reviews/verification-increment-review.md
--   (V-1 and V-2), each adding exactly one nullable column so the entities and the
--   running code stay in lock-step with the schema (ddl-auto=validate, ADR-0005).
--
-- WHY V7 (and not V8+/V2x/V3x): V6 is the highest applied identity migration on this
--   branch; the V8+ and V2x/V3x/V4x blocks are reserved by other in-flight modules.
--   This is the next free identity slot. Forward-only — never edit once applied
--   (CLAUDE.md §12); add a new migration for any later change.
--
-- -----------------------------------------------------------------------------
-- (V-1, P2) — profile.electoral_changed_at — anchor the manual electoral-change
--   cooldown (D13) to the PROFILE, not to a deletable profile_location row.
--
-- THE HOLE THIS CLOSES (review V-1): the 6-month manual-electoral cooldown used to
--   read electoral_changed_at off the *current electoral ROW*. A citizen could
--   soft-delete that (non-authoritative) row, then set a DIFFERENT pin electoral —
--   the new target's row-level electoral_changed_at was NULL, the cooldown branch
--   was skipped, and the move went through immediately. Net: unlimited manual
--   electoral hopping → exactly the cross-location double-influence D13 exists to
--   prevent (rate two MPs / sign two constituency petitions over time), and the
--   deny path never fired so the attempt was also unaudited.
--
--   The fix moves the cooldown anchor to a row the citizen CANNOT delete: the
--   profile itself. LocationService stamps profile.electoral_changed_at on EVERY
--   electoral set/change — manual AND voter-ID-authoritative — and reads the
--   cooldown from it before any manual set/move. Deleting the electoral pin no
--   longer resets the clock, because the clock no longer lives on the pin.
-- -----------------------------------------------------------------------------
ALTER TABLE profile
    ADD COLUMN electoral_changed_at TIMESTAMP WITH TIME ZONE;  -- (V-1) profile-anchored last electoral-change instant; backs the manual-change cooldown (D13). NULL until the first electoral is set.

COMMENT ON COLUMN profile.electoral_changed_at IS
    'PROFILE-ANCHORED last electoral-change instant (UTC). Backs the manual isElectoral change cooldown (D13). Stamped on EVERY electoral set/change (manual + voter-ID-authoritative). WHY on the profile, not on profile_location: a row-anchored timestamp was bypassable by remove-then-re-add of the electoral pin (review V-1); the profile cannot be deleted by the citizen, so the cooldown holds. NULL until the first electoral is set.';


-- -----------------------------------------------------------------------------
-- (V-2, P2) — app_user.last_totp_step — make the staff TOTP second factor
--   single-use via TOTP time-step monotonicity (standard replay defence).
--
-- THE HOLE THIS CLOSES (review V-2): completeTotpLogin verified the MFA_CHALLENGE
--   JWT and the TOTP code but marked NEITHER used. A captured (mfaToken, totp) pair
--   could be replayed within the ~30s step window (and until the ~5-min challenge
--   expired) to mint EXTRA independent token families the victim could not see —
--   defeating the single-use intent of a second factor on the highest-value
--   (staff/admin) session.
--
--   The fix tracks the last accepted TOTP time-step per account and refuses any code
--   whose step <= last_totp_step. The first LOGIN redemption advances the watermark, so
--   the identical code (same step) cannot be accepted again — the replay is rejected and
--   audited (AUTH_MFA_CHALLENGE_FAILED, reason REPLAY). The watermark is advanced ONLY on
--   the login TOTP step, NOT on `activate`: a citizen who has just enrolled may complete
--   their first login in the same step with the same code, and advancing on activate would
--   wrongly flag that first login as a replay.
--
--   BIGINT: the TOTP step is epochSeconds / stepSeconds — a Unix-time counter that
--   overflows int; bigint matches the entity's `long`. NULLABLE: only MFA-enrolled
--   (staff) accounts ever populate it; citizens leave it NULL = "no code accepted
--   yet" (treated as -1, so any first valid step is accepted).
-- -----------------------------------------------------------------------------
ALTER TABLE app_user
    ADD COLUMN last_totp_step BIGINT;  -- (V-2) highest accepted TOTP time-step; a presented code whose step <= this is a replay and is rejected (N-4). NULL until the first TOTP code is accepted.

COMMENT ON COLUMN app_user.last_totp_step IS
    'Highest accepted TOTP time-step (epochSeconds / stepSeconds) for the staff second factor. A presented code whose step <= last_totp_step is a REPLAY and is rejected + audited (review V-2, N-4). Advanced ONLY on a successful login TOTP step (not on activate, so a same-step first login is not mis-flagged). NULL = no code accepted yet (treated as -1). Not PII.';
