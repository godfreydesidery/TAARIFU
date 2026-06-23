package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.PromiseStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public response DTO for a {@link com.taarifu.accountability.domain.model.Promise}
 * (PRD §10 Epic M6, US-6.3).
 *
 * @param id               the promise's public id.
 * @param representativeId the subject representative's public id (institutions module).
 * @param text             the promise text.
 * @param madeAt           when the promise was made.
 * @param status           the current tracked status.
 * @param evidenceRef      object-store evidence reference, or {@code null}.
 * @param linkedProjectIds linked project public ids (projects module; may be empty).
 */
public record PromiseDto(
        UUID id,
        UUID representativeId,
        String text,
        LocalDate madeAt,
        PromiseStatus status,
        String evidenceRef,
        List<UUID> linkedProjectIds
) {
}
