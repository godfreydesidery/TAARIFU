package com.taarifu.common.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The structured error payload carried inside {@link ApiResponse#data()} on <b>every</b> error
 * response (PRD §17, ADR-0008, ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: preserves the <b>stable machine error code</b> after the top-level envelope
 * field was changed from a {@code String code} (machine code) to an {@code int statusCode} (HTTP
 * status). Because several distinct domain errors share one HTTP status — e.g. {@code TIER_TOO_LOW},
 * {@code OUT_OF_SCOPE} and {@code CONFLICT_OF_INTEREST} are all {@code 403}, and
 * {@code CONFLICT}/{@code DUPLICATE_IDENTITY} are both {@code 409} — the status alone is NOT enough
 * for a client to branch on. The machine code therefore lives here, at {@code data.code}, so clients
 * keep their stable, language-independent discriminator (ADR-0008, ADR-0010).</p>
 *
 * <p>Field contract:</p>
 * <ul>
 *   <li>{@code code} — the {@link com.taarifu.common.error.ErrorCode#name()} (e.g.
 *       {@code TIER_TOO_LOW}, {@code NOT_FOUND}). Clients branch on this, never on
 *       {@code message}.</li>
 *   <li>{@code errors} — field-level Bean Validation failures for a
 *       {@link com.taarifu.common.error.ErrorCode#VALIDATION_FAILED} response; {@code null}
 *       (and so omitted from JSON) for non-validation errors.</li>
 * </ul>
 *
 * <p>WHY a {@code record} + {@code @JsonInclude(NON_NULL)}: the payload is an immutable value object
 * (CLAUDE.md §8) and omitting a {@code null} {@code errors} keeps non-validation error bodies lean
 * for feature-phone/2G clients (PRD §15 data-budget NFR). This record also subsumes the former
 * {@code ErrorDetail.ValidationErrors} wrapper — the validation list now lives directly on
 * {@code errors}, so there is one shape for all errors (DRY, CLAUDE.md §3).</p>
 *
 * @param code   stable machine error code clients branch on ({@code ErrorCode.name()}).
 * @param errors field-level validation errors, or {@code null} for non-validation errors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        List<ErrorDetail> errors
) {
}
