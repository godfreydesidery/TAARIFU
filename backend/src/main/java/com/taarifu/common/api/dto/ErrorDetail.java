package com.taarifu.common.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A single field-level error entry returned inside {@code data.errors[]} of the envelope when a
 * request fails Bean Validation (PRD §17, ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: lets a client highlight the exact offending field. The
 * {@link com.taarifu.common.error.GlobalExceptionHandler} maps each
 * {@code MethodArgumentNotValidException} field error into one of these.</p>
 *
 * <p>WHY {@code code} is separate from {@code message}: {@code code} is a stable, language-independent
 * machine token (e.g. {@code NotBlank}); {@code message} is the localised human text (ADR-0010).
 * {@code field} may be {@code null} for cross-field/object-level violations.</p>
 *
 * @param field   the rejected field path, or {@code null} for object-level errors.
 * @param code    stable machine code for the violated constraint.
 * @param message localised human-readable description of the violation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(
        String field,
        String code,
        String message
) {

    /**
     * Convenience wrapper carrying the validation error list as the {@code data} payload, so the
     * single envelope's {@code data} field stays a structured object (not a bare array).
     *
     * @param errors the collected field-level errors.
     */
    public record ValidationErrors(List<ErrorDetail> errors) {
    }
}
