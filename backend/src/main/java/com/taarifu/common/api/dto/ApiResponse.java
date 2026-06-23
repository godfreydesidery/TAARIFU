package com.taarifu.common.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The single response envelope for <b>every</b> Taarifu HTTP response — success and error alike
 * (PRD §17, ADR-0008, ARCHITECTURE.md §5.1).
 *
 * <p>Responsibility: replaces the three inconsistent legacy envelopes with one shape so clients
 * (Angular/Flutter/USSD gateway) write a single response handler. Controllers <b>never</b>
 * hand-build this; they call {@link com.taarifu.common.api.ResponseFactory}.</p>
 *
 * <p>Field contract:</p>
 * <ul>
 *   <li>{@code success} — boolean outcome flag.</li>
 *   <li>{@code statusCode} — the <b>integer HTTP status</b> of the response ({@code 200}, {@code 201},
 *       {@code 400}, {@code 403}, {@code 404}, {@code 409}, {@code 429}, {@code 500} …). Lets a client
 *       read the outcome class without parsing the transport status line. WHY this replaced the former
 *       {@code String code} (machine code): the stable, language-independent machine code is preserved
 *       on error responses inside {@code data} (see {@link ApiError#code()}) — it is NOT lost. The
 *       int status alone cannot discriminate distinct domain errors that share a status (e.g.
 *       {@code TIER_TOO_LOW}/{@code OUT_OF_SCOPE}/{@code CONFLICT_OF_INTEREST} are all {@code 403}),
 *       which is exactly why the machine code remains at {@code data.code} (ADR-0008, ADR-0010).</li>
 *   <li>{@code message} — <b>localised</b> human text (Swahili default, English secondary —
 *       ADR-0010). Resolved server-side from an i18n key.</li>
 *   <li>{@code data} — on success, the payload; on error, an {@link ApiError} carrying the machine
 *       {@code code} (and field {@code errors[]} for validation failures).</li>
 *   <li>{@code meta} — pagination/extra metadata; {@code null} when not paginated.</li>
 *   <li>{@code timestamp} — server {@link Instant}, ISO-8601 UTC.</li>
 * </ul>
 *
 * <p>WHY a {@code record}: the envelope is an immutable value object; records give value semantics
 * and a compact, unambiguous definition (CLAUDE.md §8). {@code @JsonInclude(NON_NULL)} omits
 * {@code data}/{@code meta} when absent to keep payloads lean for feature-phone/2G clients
 * (PRD §15 data-budget NFR).</p>
 *
 * @param <T> the type of the {@code data} payload.
 * @param success whether the request succeeded.
 * @param statusCode integer HTTP status of the response (e.g. {@code 200}, {@code 403}); the stable
 *                   machine code lives at {@code data.code} on error responses.
 * @param message localised human-readable message (SW default).
 * @param data    on success the response payload; on error an {@link ApiError} (machine code + optional
 *                field errors).
 * @param meta    pagination/extra metadata, or {@code null} when not applicable.
 * @param timestamp server response instant (UTC).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        int statusCode,
        String message,
        T data,
        PageMeta meta,
        Instant timestamp
) {
}
