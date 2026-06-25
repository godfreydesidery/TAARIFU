package com.taarifu.identity.api.dto;

import java.util.UUID;

/**
 * A privacy-minimised, public read view of an identity {@link com.taarifu.identity.domain.model.Profile} —
 * the profile's public id, its display name, and its public trust-tier label — published on
 * {@link com.taarifu.identity.api.ProfileLookupApi} for sibling modules that hold a profile (or account)
 * reference and need to <b>display who authored something</b> (a petition creator, a question asker, a poll
 * author — PRD §12.2; ADR-0013 §1) together with how strongly that author's identity is established.
 *
 * <p>Responsibility: carry the profile-public-id ↔ (display-name, tier) pairing across the identity boundary as
 * a plain record — no {@code Profile} entity, no repository — so a caller (engagement/accountability/
 * communications) never imports identity's internals (ARCHITECTURE §3.2). It mirrors the established
 * {@link UserAdminSummary}/{@link com.taarifu.identity.api.dto.RecipientContact} api-DTO shape.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA — data minimisation):</b> this view carries <b>only</b> the immutable
 * public id, the public display name (first+last / org name — civic display data deliberately collected to
 * be shown, per {@link com.taarifu.identity.domain.model.Profile#displayName()}), and the public trust-tier
 * <b>name</b>. It <b>never</b> carries the national/voter {@code idNo} or its blind index, the phone or email
 * (contact PII — those resolve via the consent-fenced {@code RecipientContactApi}), or any demographic. An
 * anonymised (erased) profile resolves to its {@code anonymized_user_<short>} tombstone label and a reset tier
 * here — never resurrected PII. The {@code profilePublicId} is the only identifier a caller acts on (ADR-0006).</p>
 *
 * <p>WHY the tier is a {@code String} name, not the {@code TrustTier} enum: the tier crosses the module boundary
 * as its label ("T0".."T3") so a caller never imports identity's internal {@code TrustTier} domain enum — the
 * same convention {@link UserAdminSummary#trustTier()} follows (ADR-0013 §3). WHY tier is public-safe: it is the
 * non-sensitive "how verified is this author?" badge (PRD §7.3), deliberately shown on civic surfaces; it leaks
 * no ID/contact value — only a verification level a sibling view renders as a badge next to the author's name.</p>
 *
 * @param profilePublicId the profile's immutable public id (the cross-module display/author handle).
 * @param displayName     the profile's public display name (first+last, or org name), or {@code null} if the
 *                        profile has not set a name yet — callers render {@code null} as "name unknown", never
 *                        an error.
 * @param tier            the profile's <b>live</b> public trust-tier name ({@code "T0".."T3"}, PRD §7.3),
 *                        resolved server-side from live state ({@code TierService}, MF-2) — the author's
 *                        verification badge. Never the JWT tier hint; never PII.
 */
public record ProfileSummary(UUID profilePublicId, String displayName, String tier) {
}
