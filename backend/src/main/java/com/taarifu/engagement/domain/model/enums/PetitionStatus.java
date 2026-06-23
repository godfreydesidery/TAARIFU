package com.taarifu.engagement.domain.model.enums;

/**
 * Lifecycle state of a {@link com.taarifu.engagement.domain.model.Petition} (PRD §9.1, §12.2 M9).
 *
 * <p>Responsibility: the state machine a petition moves through —
 * {@code DRAFT → (moderation) → ACTIVE → SUCCEEDED → RESPONDED → CLOSED} (PRD §12.2). Defined as a
 * stable enum so the DB {@code CHECK} constraint, JPA, and the API all branch on one vocabulary
 * (DRY, CLAUDE.md §3).</p>
 *
 * <p>WHY {@code SUCCEEDED} is distinct from {@code RESPONDED}: reaching the signature goal
 * ({@code SUCCEEDED}) is a citizen-side milestone that notifies the target (UC-E04); the target's
 * published answer is a separate, later transition ({@code RESPONDED} — UC-E05). Conflating them would
 * lose the "reached threshold but target has not yet answered" reality that drives accountability
 * (PRD §12.2). A petition may also be {@code CLOSED} on deadline without ever succeeding.</p>
 */
public enum PetitionStatus {

    /** Authoring; not yet submitted for moderation. Not publicly visible (PRD §22.6 — drafts excluded). */
    DRAFT,

    /** Submitted and approved by moderation; publicly visible and collecting signatures (UC-E02→ACTIVE). */
    ACTIVE,

    /** Signature goal reached; target notified (UC-E04). Still publicly visible. */
    SUCCEEDED,

    /** The target representative/office has published a response (UC-E05). */
    RESPONDED,

    /** Terminal: closed by the creator/moderator or on deadline expiry (PRD §12.2). */
    CLOSED
}
