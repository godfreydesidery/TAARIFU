package com.taarifu.admin.api.dto;

/**
 * The outcome of a DLQ replay (P3-1; response of {@code POST /api/v1/admin/outbox/replay}): how many FAILED
 * outbox rows were actually re-queued to PENDING, and which mode ran.
 *
 * <p>Responsibility: report the count of rows the replay <b>actually moved</b> — never an over-count, because
 * the kernel pins {@code WHERE status='FAILED'} so a row already re-queued (or unknown) matches nothing.
 * {@code requeued=0} is therefore a normal idempotent outcome (the id was already replayed, or the window
 * matched no remaining FAILED rows), not an error. The console shows the count and may loop a window replay
 * until it returns 0 to drain a large DLQ.</p>
 *
 * @param mode     {@code "BY_ID"} for a single-id replay, {@code "BY_WINDOW"} for a bounded-window replay.
 * @param requeued the number of FAILED rows actually moved to PENDING this call (0 on the idempotent path).
 */
public record ReplayOutboxResultDto(String mode, int requeued) {
}
