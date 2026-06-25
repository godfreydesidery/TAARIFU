package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.PromiseStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response DTO for one entry in a promise's status timeline
 * (PRD &sect;10 Epic M6, US-6.3 "citizen-visible timeline").
 *
 * <p>Each entry is one recorded transition (or the originating MADE state) — the append-only provenance of
 * how a representative's commitment moved over time. Exposes only public ids and the entry's own data.</p>
 *
 * @param id          the timeline entry's public id.
 * @param status      the status the promise moved to at this entry.
 * @param evidenceRef supporting evidence object-store reference, or {@code null}.
 * @param note        optional curator note, or {@code null}.
 * @param recordedAt  when this transition was recorded (the entry's creation time).
 */
public record PromiseStatusEntryDto(
        UUID id,
        PromiseStatus status,
        String evidenceRef,
        String note,
        Instant recordedAt
) {
}
