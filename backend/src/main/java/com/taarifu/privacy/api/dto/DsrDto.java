package com.taarifu.privacy.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The boundary view of a tracked data-subject request (UC-A17/UC-S09; ADR-0016 §3).
 *
 * <p>Responsibility: the projection of a {@link com.taarifu.privacy.domain.model.DataSubjectRequest} returned
 * to the subject (their own request status) and to the ADMIN/ROOT operator queue. References + status only —
 * never PII (the {@code subjectPublicId} is an opaque account id; there is no name/phone/ID).</p>
 *
 * @param publicId        the request's public id.
 * @param subjectPublicId the requesting account's public id (opaque; for the operator queue).
 * @param type            ACCESS or ERASURE.
 * @param status          the request lifecycle status.
 * @param requestedAt     when received (UTC).
 * @param acknowledgedAt  when acknowledged (UTC), or {@code null}.
 * @param completedAt     when completed/closed (UTC), or {@code null}.
 * @param dueAt           the completion SLA deadline (UTC).
 * @param legalHold       whether a legal hold currently suspends fulfilment.
 * @param reasonCode      the machine reason code (on hold/rejection), or {@code null}.
 */
public record DsrDto(
        UUID publicId,
        UUID subjectPublicId,
        String type,
        String status,
        Instant requestedAt,
        Instant acknowledgedAt,
        Instant completedAt,
        Instant dueAt,
        boolean legalHold,
        String reasonCode) {
}
