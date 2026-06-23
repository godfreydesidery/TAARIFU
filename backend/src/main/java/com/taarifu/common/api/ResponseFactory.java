package com.taarifu.common.api;

import com.taarifu.common.api.dto.ApiError;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.ErrorDetail;
import com.taarifu.common.api.dto.PageMeta;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.i18n.MessageResolver;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

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
     * HTTP status carried by every success envelope. WHY a constant: success responses have no
     * {@link ErrorCode} to derive a status from, so {@code 200 OK} is the agreed default for the
     * envelope's {@code statusCode} (ADR-0008); the transport-level status is set separately by the
     * controller/handler.
     */
    private static final int HTTP_OK = 200;

    /**
     * Builds a success envelope ({@code success=true}, {@code statusCode=200}) for a single payload.
     *
     * @param data the payload (may be {@code null} for no-content successes).
     * @param <T>  payload type.
     * @return the populated success envelope.
     */
    public <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, HTTP_OK, messages.resolve(ErrorCode.OK.messageKey()),
                data, null, Instant.now());
    }

    /**
     * Builds a paged success envelope ({@code statusCode=200}): payload in {@code data}, pagination
     * in {@code meta}.
     *
     * @param data the page content (typically a {@code List<Dto>}).
     * @param meta the pagination metadata from {@code PageMapper}.
     * @param <T>  payload type.
     * @return the populated paged success envelope.
     */
    public <T> ApiResponse<T> paged(T data, PageMeta meta) {
        return new ApiResponse<>(true, HTTP_OK, messages.resolve(ErrorCode.OK.messageKey()),
                data, meta, Instant.now());
    }

    /**
     * Builds an error envelope for a non-validation failure: {@code success=false},
     * {@code statusCode} = the error's HTTP status, and {@code data} = an {@link ApiError} carrying
     * the stable machine {@code code} (no field {@code errors[]}).
     *
     * @param errorCode   the typed error (drives the HTTP {@code statusCode}, the machine code, and the
     *                    localised message).
     * @param messageArgs positional arguments for the localised message template.
     * @return the populated error envelope whose {@code data} is the {@link ApiError}.
     */
    public ApiResponse<ApiError> error(ErrorCode errorCode, Object... messageArgs) {
        return error(errorCode, null, messageArgs);
    }

    /**
     * Builds an error envelope with optional field-level validation errors.
     *
     * <p>WHY the machine code moved into {@code data}: the top-level envelope field is now the integer
     * HTTP {@code statusCode}, which cannot discriminate distinct errors sharing a status (e.g.
     * {@code TIER_TOO_LOW}/{@code OUT_OF_SCOPE}/{@code CONFLICT_OF_INTEREST} are all {@code 403}). The
     * stable {@link ErrorCode#name()} is therefore preserved at {@code data.code} so clients keep a
     * language-independent discriminator (ADR-0008, ADR-0010).</p>
     *
     * @param errorCode    the typed error (drives the HTTP {@code statusCode}, the machine code, and
     *                     the localised message).
     * @param fieldErrors  field-level validation errors to surface at {@code data.errors[]}, or
     *                     {@code null} for non-validation errors (then {@code errors} is omitted).
     * @param messageArgs  positional arguments for the localised message template.
     * @return the populated error envelope whose {@code data} is the {@link ApiError}.
     */
    public ApiResponse<ApiError> error(ErrorCode errorCode, List<ErrorDetail> fieldErrors,
                                       Object... messageArgs) {
        ApiError payload = new ApiError(errorCode.name(), fieldErrors);
        return new ApiResponse<>(false, errorCode.httpStatus().value(),
                messages.resolve(errorCode.messageKey(), messageArgs), payload, null, Instant.now());
    }
}
