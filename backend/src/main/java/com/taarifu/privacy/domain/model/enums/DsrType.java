package com.taarifu.privacy.domain.model.enums;

/**
 * The kind of data-subject-right being exercised (PRD §18 PDPA, UC-A17/UC-S09; ADR-0016 §3).
 *
 * <p>Responsibility: the two data-subject rights Taarifu honours at launch. Stable, append-only token
 * persisted on {@link com.taarifu.privacy.domain.model.DataSubjectRequest}; not PII.</p>
 */
public enum DsrType {

    /**
     * Right of access — the subject requests an <b>export</b> of the personal data held about them
     * (UC-A16/UC-S09). Fulfilled synchronously by aggregating each module's published export contributor.
     */
    ACCESS,

    /**
     * Right to erasure ("right to be forgotten") — the subject requests deletion of their personal data
     * (UC-A17/UC-S09). Fulfilled as <b>de-identification + tombstoning</b>, not row deletion (PRD §25.1):
     * PII is severed and the civic record is kept de-identified. Driven asynchronously by the
     * {@code ERASURE_REQUESTED} outbox event each owning module reacts to.
     */
    ERASURE
}
