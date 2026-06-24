package com.taarifu.admin.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * Request to re-queue terminally FAILED transactional-outbox events back to PENDING for reprocessing
 * (P3-1; backs {@code POST /api/v1/admin/outbox/replay}). After the underlying cause of a failure has been
 * fixed (a downstream dependency recovered, a handler bug deployed), an operator drains the DLQ either by a
 * single event id or by a bounded window.
 *
 * <p><b>Two mutually exclusive modes</b> (the service rejects an ambiguous/empty body):</p>
 * <ul>
 *   <li><b>By id:</b> set {@link #eventId} — re-queues exactly that one FAILED row (a no-op if it is unknown
 *       or already re-queued — replay is idempotent).</li>
 *   <li><b>By window:</b> leave {@link #eventId} {@code null} and optionally narrow with {@link #eventType}
 *       and/or a {@link #failedFrom}/{@link #failedTo} window over the FAILED-time, capped by {@link #limit}.
 *       All window fields are optional — an empty window replays the whole DLQ one bounded batch at a time.</li>
 * </ul>
 *
 * <p>WHY no PII and no actor field: the body carries only references (a public event id), a taxonomy key,
 * timestamps, and a numeric cap — never PII (PRD §18). The <b>acting admin</b> is always the authenticated
 * caller from the security context, never a body field, so a request can never attribute the replay to a
 * different operator (mirrors the user-admin fence).</p>
 *
 * @param eventId    the single FAILED outbox row to re-queue; {@code null} selects window mode.
 * @param eventType  (window mode) restrict to this event taxonomy key, or {@code null} for any.
 * @param failedFrom (window mode) inclusive lower bound on the FAILED-time, or {@code null}.
 * @param failedTo   (window mode) inclusive upper bound on the FAILED-time, or {@code null}.
 * @param limit      (window mode) max rows to re-queue this call; {@code null} uses the kernel's default cap.
 *                   Bounded {@code [1, 1000]} so a single call can never trigger an unbounded relay surge.
 */
public record ReplayOutboxRequest(
        UUID eventId,
        String eventType,
        Instant failedFrom,
        Instant failedTo,
        @Positive @Max(1000) Integer limit) {

    /**
     * @return {@code true} if this is a single-id replay (an {@link #eventId} was supplied); {@code false}
     *         selects the bounded-window mode.
     */
    public boolean isById() {
        return eventId != null;
    }
}
