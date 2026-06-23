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
 *   <li>{@code code} — <b>stable machine code</b> ({@code OK}, {@code NOT_FOUND}, {@code TIER_TOO_LOW}
 *       …). Clients branch on this, never on {@code message} (ADR-0008, ADR-0010).</li>
 *   <li>{@code message} — <b>localised</b> human text (Swahili default, English secondary —
 *       ADR-0010). Resolved server-side from an i18n key.</li>
 *   <li>{@code data} — the payload; {@code null} on error.</li>
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
 * @param code    stable machine code clients branch on.
 * @param message localised human-readable message (SW default).
 * @param data    the response payload, or {@code null} on error.
 * @param meta    pagination/extra metadata, or {@code null} when not applicable.
 * @param timestamp server response instant (UTC).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        PageMeta meta,
        Instant timestamp
) {
}
