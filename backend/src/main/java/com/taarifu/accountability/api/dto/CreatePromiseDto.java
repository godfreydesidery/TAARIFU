package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Request to create a curated {@link com.taarifu.accountability.domain.model.Promise}
 * (PRD §10 Epic M6, US-6.3; D-Q4 curated authorship).
 *
 * <p>Accepted only from an authorised author / {@code ROLE_ADMIN}. {@code status} is optional and
 * defaults to {@link PromiseStatus#MADE}.</p>
 *
 * @param representativeId the subject representative's public id (required).
 * @param text             the promise text (required).
 * @param madeAt           when the promise was made (required).
 * @param status           initial status, or {@code null} for {@link PromiseStatus#MADE}.
 * @param evidenceRef      object-store evidence reference, or {@code null}.
 * @param linkedProjectIds linked project public ids, or {@code null}/empty.
 */
public record CreatePromiseDto(
        @NotNull(message = "accountability.representative.required") UUID representativeId,
        @NotBlank(message = "accountability.promise.text.required")
        @Size(max = 2000, message = "accountability.promise.text.tooLong") String text,
        @NotNull(message = "accountability.promise.madeAt.required") LocalDate madeAt,
        PromiseStatus status,
        @Size(max = 512, message = "accountability.evidenceRef.tooLong") String evidenceRef,
        Set<UUID> linkedProjectIds
) {
}
