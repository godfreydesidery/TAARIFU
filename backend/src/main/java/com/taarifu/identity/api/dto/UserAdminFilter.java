package com.taarifu.identity.api.dto;

/**
 * The PII-safe filter for the admin user-management list (M14 admin console; PRD §7.2 RBAC, US-14.1),
 * published on {@link com.taarifu.identity.api.UserAdminQueryApi}.
 *
 * <p>Responsibility: carry exactly the filter dimensions the back-office Users table needs and nothing else.
 * Every field is optional ({@code null}/blank = no constraint on that dimension); the admin controller builds
 * this from request params and the identity side resolves it over its own tables. WHY a typed query record
 * (not a bag of nullable service args): keeps the cross-module contract explicit and append-only, and lets
 * the admin module name its filters without importing identity's internals (ADR-0013 §1).</p>
 *
 * <p>WHY {@code phoneSuffix} (not a full-phone search): an operator typically knows only the last few digits
 * a citizen reads back; searching by suffix supports that while the full number stays masked everywhere it is
 * displayed (PRD §18). The implementation matches the suffix against the stored phone but never returns the
 * raw number. WHY {@code tier}/{@code role}/{@code status} are enum <i>names</i> as {@code String}: the
 * identity enums ({@code TrustTier}/{@code RoleName}/{@code UserStatus}) are identity-internal domain types
 * and must not leak across the module boundary as types (CLAUDE.md §8); the impl parses the name and treats
 * an unknown value as "no match" rather than throwing — a stale admin filter never 500s the table.</p>
 *
 * @param name        free-text fragment matched (case-insensitively) against the profile display name;
 *                    {@code null}/blank means no name filter.
 * @param phoneSuffix trailing digits of the phone to match (e.g. the last 4); {@code null}/blank means no
 *                    phone filter. Never the full number; the result still masks the phone.
 * @param tier        trust-tier name to filter to ({@code T0}–{@code T3}); {@code null}/blank means any tier.
 * @param role        role name to filter to (only accounts holding an <b>active</b> grant of this role);
 *                    {@code null}/blank means any role. An unknown role name yields an empty page.
 * @param status      account-status name to filter to ({@code ACTIVE}/{@code SUSPENDED}/…); {@code null}/
 *                    blank means any status.
 */
public record UserAdminFilter(
        String name,
        String phoneSuffix,
        String tier,
        String role,
        String status) {
}
