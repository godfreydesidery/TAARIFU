package com.taarifu.identity.api.dto;

import java.util.UUID;

/**
 * The caller's own account+profile snapshot ({@code GET /me}) — AUTH-DESIGN §14.2.
 *
 * <p>The owner reads themselves, so their own phone/email are included; it <b>never</b> carries
 * {@code idNo} or any other user's PII/location (PRD §18, §9.0). The {@code tier} is the <b>live</b>
 * tier from the same resolver used for gating (MF-2) — a UI hint, never an authorization input here.</p>
 *
 * @param userPublicId  the account public id.
 * @param phone         the caller's own phone (owner-readable).
 * @param firstName     given/first name, or {@code null}.
 * @param lastName      family name, or {@code null}.
 * @param email         the caller's own email, or {@code null}.
 * @param tier          the live trust tier name (T0–T3).
 * @param phoneVerified whether phone is verified.
 * @param emailVerified whether email is verified.
 * @param idVerified    whether the government ID is verified.
 */
public record MeDto(UUID userPublicId, String phone, String firstName, String lastName, String email,
                    String tier, boolean phoneVerified, boolean emailVerified, boolean idVerified) {
}
