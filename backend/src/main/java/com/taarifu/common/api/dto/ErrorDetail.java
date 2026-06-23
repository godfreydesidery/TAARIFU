package com.taarifu.common.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single field-level error entry returned inside {@code data.errors[]} of the envelope when a
 * request fails Bean Validation (PRD §17, ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: lets a client highlight the exact offending field. The
 * {@link com.taarifu.common.error.GlobalExceptionHandler} maps each
 * {@code MethodArgumentNotValidException} field error into one of these, and they are carried in
 * {@link ApiError#errors()} (which is itself the {@code data} of the error envelope).</p>
 *
 * <p>WHY {@code code} is separate from {@code message}: {@code code} is a stable, language-independent
 * machine token (e.g. {@code NotBlank}); {@code message} is the localised human text (ADR-0010).
 * {@code field} may be {@code null} for cross-field/object-level violations.</p>
 *
 * <p>WHY there is no {@code ValidationErrors} wrapper any more: the validation list now lives directly
 * on {@link ApiError#errors()}, so a single error shape covers both validation and non-validation
 * failures (DRY, CLAUDE.md §3) — the former nested {@code ValidationErrors} record was removed.</p>
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
}
