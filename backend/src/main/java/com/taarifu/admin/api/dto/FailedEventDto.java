package com.taarifu.admin.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin dead-letter-queue (DLQ) list — a <b>PII-free</b> view of a terminally FAILED
 * transactional-outbox event (P3-1; backs {@code GET /api/v1/admin/outbox/failed}).
 *
 * <p>Responsibility: give an operator exactly enough to triage and decide what to replay — the event's
 * public id (which a single-id replay addresses), its taxonomy {@code eventType} (which a bulk replay can
 * scope to), the {@code attempts} it took before terminal failure, when it failed, and the failure's age in
 * seconds — and <b>nothing else</b>. It is mapped 1:1 from the shared-kernel
 * {@link com.taarifu.common.outbox.OutboxReplayService.FailedOutboxView} projection.</p>
 *
 * <p><b>🔒 Privacy / no leak (PRD §18, ADR-0014 §1, CLAUDE.md §12):</b> the outbox {@code payload} (an
 * internal serialised body — ids/codes/enums only) and the redacted {@code last_error} text are
 * <b>deliberately not exposed</b> here. The list surface shows diagnostics dimensions, never an internal
 * event body or any free-text error fragment. By construction this DTO carries no PII.</p>
 *
 * @param eventId    the FAILED outbox row's public id — pass this to a single-id replay.
 * @param eventType  the event taxonomy key (e.g. {@code REPORT_ROUTED}) — usable to scope a bulk replay.
 * @param attempts   number of dispatch attempts made before the row was terminally FAILED.
 * @param failedAt   when the row reached the terminal FAILED state (UTC), or {@code null} if unknown.
 * @param ageSeconds age of the failure in whole seconds at read time (non-negative diagnostics hint).
 */
public record FailedEventDto(
        UUID eventId,
        String eventType,
        int attempts,
        Instant failedAt,
        long ageSeconds) {
}
