package com.taarifu.identity.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The identity module's minimised slice of a data-subject ACCESS export — the subject's own account/profile
 * summary (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: the boundary shape {@code IdentityExportContributor} returns for the privacy module's
 * export aggregation. It is the subject's <b>own</b> data, returned to the subject.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, ADR-0016 §4):</b> the national/voter <b>ID number is deliberately
 * excluded</b> — an export must not become a fresh plaintext copy of the most sensitive PII in transit. Only
 * the ID <b>type</b> and the <b>verification state</b> are surfaced, so the subject can see <i>that</i> they
 * verified and with which document kind, without the number leaving the encrypted column. If Legal mandates
 * including the number in a subject's own export, it is a single contributor-field change (CENTRAL NEED).
 * {@code ProfileLocation}s (private PII, PRD §9.0/§22.1) are summarised as a <b>count</b>, not enumerated.</p>
 *
 * @param accountPublicId the subject's account public id.
 * @param displayName     the profile display name (first + last, or org name), or {@code null}.
 * @param phone           the subject's <b>own</b> phone in E.164 — included because the subject is requesting
 *                        their own data (their right of access), unlike the masked admin view.
 * @param email           the optional email, or {@code null}.
 * @param idType          the government ID document type ({@code NIDA}/{@code VOTER}/{@code PASSPORT}), or
 *                        {@code null} — the <b>number is never included</b>.
 * @param idVerified      whether the government ID is verified (T3 gate); no ID value leaks.
 * @param trustTier       the account's trust tier (T0–T3).
 * @param dateOfBirth     date of birth, or {@code null}.
 * @param gender          gender, or {@code null}.
 * @param nationality     nationality code, or {@code null}.
 * @param locationCount   the number of pinned locations (count only — the pins themselves are not enumerated).
 * @param createdAt       when the account was created (UTC).
 */
public record IdentityExportView(
        UUID accountPublicId,
        String displayName,
        String phone,
        String email,
        String idType,
        boolean idVerified,
        String trustTier,
        LocalDate dateOfBirth,
        String gender,
        String nationality,
        long locationCount,
        Instant createdAt) {
}
