package com.taarifu.privacy.domain.model.enums;

/**
 * The lifecycle of a {@link com.taarifu.privacy.domain.model.DataSubjectRequest} (PRD §25.1 SLA; ADR-0016 §3).
 *
 * <p>Responsibility: tracks a DSR from receipt to completion so the controller can <b>demonstrate</b> the
 * PDPA obligation was met within SLA (acknowledge ≤72h, complete ≤30 days — PRD §25.1). Append-only token;
 * not PII. Legal hold parks a request at {@link #ON_HOLD} (an item under investigation is exempt from
 * erasure until released — §25.1).</p>
 */
public enum DsrStatus {

    /** Just received; the SLA clock has started (acknowledge within 72h). */
    RECEIVED,

    /** Acknowledged to the subject (PRD §25.1 ≤72h obligation met). */
    ACKNOWLEDGED,

    /** Fulfilment underway (export being assembled, or erasure fan-out dispatched). */
    IN_PROGRESS,

    /** Fully fulfilled (export delivered, or all erasure handlers completed). Terminal. */
    COMPLETED,

    /** Refused with a reason code (e.g. could not verify the subject, or a lawful exemption). Terminal. */
    REJECTED,

    /** Suspended by a <b>legal hold</b> — an item under investigation is exempt from erasure (§25.1). */
    ON_HOLD
}
