package com.taarifu.identity.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A privacy-minimised account row returned to the admin user-management list (M14, US-14.1, UC-H06;
 * ADR-0013 §1), published on {@link com.taarifu.identity.api.UserAdminQueryApi}.
 *
 * <p>Responsibility: the read-side projection identity hands the admin console for an account row. It
 * crosses the module boundary as a plain record — no {@code User} entity, no repository — so admin never
 * imports identity's internals (ARCHITECTURE §3.2). Mirrors the established
 * {@link com.taarifu.reporting.api.dto.AdminReportSummary} shape.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA — data minimisation):</b> this view carries the <b>masked</b> phone
 * only (e.g. {@code +2557****1234}), never the full E.164 number, <b>never</b> the {@code idNo} (national/
 * voter ID), and never any decrypted PII. The {@code displayName} is the profile's first/last name (already
 * collected for civic use, not sensitive ID PII) shown so an operator can recognise an account; it is
 * {@code null} for an account that has not completed its profile. The immutable {@code publicId} is the only
 * identifier the console acts on (ADR-0006).</p>
 *
 * @param publicId    the account's immutable public id (the only id admin commands reference).
 * @param displayName the profile's display name (first + last, or org name), or {@code null} if unset.
 * @param maskedPhone the account phone with the middle digits masked; never the raw number.
 * @param trustTier   the account's cached trust-tier name (T0–T3); an operational hint for the operator.
 * @param status      the account lifecycle status name ({@code PENDING}/{@code ACTIVE}/{@code SUSPENDED}/
 *                    {@code DISABLED}).
 * @param roles       the names of the account's currently <b>active</b> roles (additive — §6.4); never empty
 *                    for an active account (at least {@code CITIZEN}).
 * @param createdAt   when the account was created (UTC), for sorting/triage.
 */
public record UserAdminSummary(
        UUID publicId,
        String displayName,
        String maskedPhone,
        String trustTier,
        String status,
        List<String> roles,
        Instant createdAt) {
}
