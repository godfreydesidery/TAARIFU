package com.taarifu.common.api;

import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.PageMeta;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.i18n.MessageResolver;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The single builder for the {@link ApiResponse} envelope (PRD §17, ADR-0008,
 * ARCHITECTURE.md §5.1).
 *
 * <p>Responsibility: controllers and the {@link com.taarifu.common.error.GlobalExceptionHandler}
 * call this — they <b>never</b> hand-build the envelope or its JSON. Centralising construction here
 * guarantees one shape, a server timestamp on every response, and a <b>localised</b> message
 * resolved from the {@link ErrorCode}'s i18n key (Swahili default — ADR-0010).</p>
 *
 * <p>WHY a Spring bean (not static helpers): it needs the injected {@link MessageResolver}, and being
 * a bean keeps it mockable in slice tests (CLAUDE.md §10).</p>
 */
@Component
public class ResponseFactory {

    private final MessageResolver messages;

    /**
     * @param messages resolver that localises {@link ErrorCode} message keys (SW/EN).
     */
    public ResponseFactory(MessageResolver messages) {
        this.messages = messages;
    }

    /**
     * Builds a success envelope ({@code success=true}, code {@code OK}) for a single payload.
     *
     * @param data the payload (may be {@code null} for no-content successes).
     * @param <T>  payload type.
     * @return the populated success envelope.
     */
    public <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, ErrorCode.OK.name(), messages.resolve(ErrorCode.OK.messageKey()),
                data, null, Instant.now());
    }

    /**
     * Builds a paged success envelope: payload in {@code data}, pagination in {@code meta}.
     *
     * @param data the page content (typically a {@code List<Dto>}).
     * @param meta the pagination metadata from {@code PageMapper}.
     * @param <T>  payload type.
     * @return the populated paged success envelope.
     */
    public <T> ApiResponse<T> paged(T data, PageMeta meta) {
        return new ApiResponse<>(true, ErrorCode.OK.name(), messages.resolve(ErrorCode.OK.messageKey()),
                data, meta, Instant.now());
    }

    /**
     * Builds an error envelope ({@code success=false}, {@code data} carrying any structured error
     * payload such as validation {@code errors[]}).
     *
     * @param errorCode    the typed error (drives the machine {@code code} and the localised message).
     * @param data         optional structured error payload (e.g. {@code ValidationErrors}); may be {@code null}.
     * @param messageArgs  positional arguments for the localised message template.
     * @param <T>          payload type.
     * @return the populated error envelope.
     */
    public <T> ApiResponse<T> error(ErrorCode errorCode, T data, Object... messageArgs) {
        return new ApiResponse<>(false, errorCode.name(),
                messages.resolve(errorCode.messageKey(), messageArgs), data, null, Instant.now());
    }
}
