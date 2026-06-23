package com.taarifu.accountability.api.dto;

import com.taarifu.accountability.domain.model.enums.PromiseStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to advance a {@link com.taarifu.accountability.domain.model.Promise}'s tracked status
 * (PRD §10 Epic M6, US-6.3; D-Q4).
 *
 * <p>Accepted only from an authorised author / {@code ROLE_ADMIN}. A status move (e.g. to KEPT/BROKEN)
 * is an authored, evidence-backed judgement — neutrality requires provenance, so {@code evidenceRef} is
 * encouraged.</p>
 *
 * @param status      the new status (required).
 * @param evidenceRef supporting evidence reference, or {@code null} to leave unchanged.
 */
public record UpdatePromiseStatusDto(
        @NotNull(message = "accountability.promise.status.required") PromiseStatus status,
        @Size(max = 512, message = "accountability.evidenceRef.tooLong") String evidenceRef
) {
}
