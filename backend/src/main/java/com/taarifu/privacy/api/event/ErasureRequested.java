package com.taarifu.privacy.api.event;

import java.util.UUID;

/**
 * Published domain event: a citizen exercised their <b>right to erasure</b> (PRD §18, §25.1, UC-A17/UC-S09;
 * ADR-0016 §5, ADR-0014).
 *
 * <p>Responsibility: the immutable, cross-module contract emitted when a verified ERASURE
 * {@link com.taarifu.privacy.domain.model.DataSubjectRequest} is opened. It is the trigger for each owning
 * module to <b>de-identify its share</b> of the subject's data asynchronously: {@code identity} crypto-shreds
 * the encrypted national/voter ID and tombstones the account; {@code reporting}/{@code engagement}/
 * {@code accountability}/{@code communications}/{@code media}/{@code tokens}/{@code analytics} de-identify the
 * reporter/author/recipient reference while <b>keeping</b> the de-identified civic record and counts
 * (PRD §25.1). The event is written to the transactional outbox in the same transaction as the DSR insert
 * ({@code common.outbox.OutboxWriter}) and the relay later delivers it to every registered erasure handler.</p>
 *
 * <p>WHY an immutable record in {@code api.event} (not a Spring event): events are the only async cross-module
 * contract (ARCHITECTURE §3.2/§8); keeping it in the module's public {@code api} package lets each owning
 * module subscribe without importing privacy's internals. Erasure is intentionally asynchronous + idempotent
 * (at-least-once, ADR-0014 §3) so it never blocks the citizen and a handler can be safely redelivered.</p>
 *
 * <p><b>🔒 ids ONLY — never PII</b> (PRD §18, §12): this record carries the subject's <b>account public id</b>
 * and the <b>DSR public id</b> — nothing else. No name, phone, ID, location, or report content. Each handler
 * re-reads its own data by {@link #subjectPublicId} and severs it; the subject id itself is the only PII-free
 * link, and after every handler runs even that maps only to a tombstone.</p>
 *
 * @param subjectPublicId the erasing account's public id — the key every handler re-reads its own data by.
 * @param dsrPublicId     the {@link com.taarifu.privacy.domain.model.DataSubjectRequest} public id (the
 *                        producing aggregate; lets a handler correlate back to the tracked request).
 */
public record ErasureRequested(
        UUID subjectPublicId,
        UUID dsrPublicId
) {

    /**
     * The outbox {@code eventType} taxonomy key handlers register on (ADR-0014 §4). The producer stamps it on
     * the {@code EventEnvelope} and every consuming {@code DomainEventHandler} registers on it; one shared
     * constant keeps producer and handlers in lock-step (DRY).
     */
    public static final String EVENT_TYPE = "ERASURE_REQUESTED";

    /** The outbox {@code aggregateType} for the producing data-subject-request aggregate (ADR-0014 §1). */
    public static final String AGGREGATE_TYPE = "DATA_SUBJECT";
}
