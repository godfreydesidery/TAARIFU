package com.taarifu.identity.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One account's admin detail view — roles + scopes, tier, status, and the count of pinned locations —
 * published on {@link com.taarifu.identity.api.UserAdminQueryApi} for the console's account page (M14,
 * US-14.1, UC-H06; ADR-0013 §1).
 *
 * <p>Responsibility: the boundary shape for a single account's back-office detail. It carries everything an
 * operator needs to understand and manage an account — identity (masked), lifecycle, trust tier, and the
 * full list of {@link UserRoleGrant}s with their scopes — and <b>deliberately omits</b> the raw phone, the
 * {@code idNo}, and the locations themselves.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA — data minimisation):</b> the phone is <b>masked</b>; the national/
 * voter {@code idNo} is <b>never</b> returned (it is encrypted at rest and is not an admin-management
 * field). {@code locationCount} is a bare count — the {@code ProfileLocation}s (which are private PII and
 * must never be exposed/indexed, PRD §9.0/§22.1) are <b>not</b> listed here; the operator sees only that the
 * account has N pins, never where they are. {@code idVerified} surfaces whether the account reached T3 by
 * verified government ID (so an operator can see verification state) <b>without</b> revealing any ID value.</p>
 *
 * @param publicId      the account's immutable public id.
 * @param displayName   the profile's display name (first + last, or org name), or {@code null}.
 * @param maskedPhone   the account phone with the middle digits masked; never the raw number.
 * @param email         the optional login-alias email, or {@code null} (not unique; not sensitive ID PII).
 * @param trustTier     the account's cached trust-tier name (T0–T3).
 * @param status        the account lifecycle status name.
 * @param idVerified    {@code true} if the account's government ID is verified (T3 gate); no ID value leaks.
 * @param roles         the account's role grants with their scope + effective window (additive — §6.4).
 * @param locationCount the number of pinned {@code ProfileLocation}s the account holds (count only, no PII).
 * @param createdAt     when the account was created (UTC).
 * @param lastLoginAt   the last successful login instant (UTC), or {@code null} if never logged in.
 */
public record UserAdminDetail(
        UUID publicId,
        String displayName,
        String maskedPhone,
        String email,
        String trustTier,
        String status,
        boolean idVerified,
        List<UserRoleGrant> roles,
        long locationCount,
        Instant createdAt,
        Instant lastLoginAt) {
}
