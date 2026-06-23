package com.taarifu.admin.api.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A privacy-minimised account row returned to the admin user-management list (M14, US-14.1, UC-H06;
 * ADR-0013 §1).
 *
 * <p>Responsibility: the read-side projection the {@link IdentityAdminPort} hands the admin console for an
 * account. It crosses the module boundary as a plain record — no {@code User} entity, no repository — so
 * admin never imports identity's internals (ARCHITECTURE §3.2).</p>
 *
 * <p><b>PII discipline (PRD §18, PDPA — data minimisation):</b> this view carries the <b>masked</b> phone
 * only (e.g. {@code +2557****1234}), never the full E.164 number, never the {@code idNo}, never any
 * decrypted PII. The admin surface is for account/role management, not a contact-data export; an
 * operator who needs to reach a citizen does so through the proper, consent-bound channel, not the
 * dashboard. The immutable {@code publicId} is the only identifier the console acts on (ADR-0006).</p>
 *
 * @param publicId    the account's immutable public id (the only id admin commands reference).
 * @param maskedPhone the account phone with the middle digits masked; never the raw number.
 * @param handle      the optional public display handle, or {@code null}.
 * @param status      the account lifecycle status name ({@code PENDING}/{@code ACTIVE}/{@code SUSPENDED}/
 *                    {@code DISABLED}).
 * @param trustTier   the account's cached trust-tier name (T0–T3); an operational hint for the operator.
 * @param roles       the names of the account's currently active roles (additive — §6.4); never empty for
 *                    an active account (at least {@code CITIZEN}).
 * @param createdAt   when the account was created (UTC), for sorting/triage.
 */
public record AdminUserView(
        UUID publicId,
        String maskedPhone,
        String handle,
        String status,
        String trustTier,
        List<String> roles,
        Instant createdAt) {
}
