package com.taarifu.common.error;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiError;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.api.dto.ErrorDetail;
import com.taarifu.common.i18n.MessageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * The single {@link RestControllerAdvice} that turns <b>every</b> exception into the one
 * {@link ApiResponse} envelope with the correct HTTP status (PRD §17, ADR-0008,
 * ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: controllers never {@code try/catch} for response shape — they throw typed
 * {@link ApiException}s (or let framework exceptions propagate) and this advice maps them uniformly.
 * Field-level Bean Validation failures are collapsed into {@code data.errors[]}.</p>
 *
 * <p>Security/PDPA invariants enforced here (PRD §18):</p>
 * <ul>
 *   <li><b>No stack traces or PII ever reach the client.</b> Unexpected faults return a generic
 *       {@link ErrorCode#INTERNAL_ERROR} message; the real cause is logged server-side only.</li>
 *   <li>Auth failures are normalised to {@link ErrorCode#UNAUTHENTICATED}/{@link ErrorCode#FORBIDDEN}
 *       so the response never reveals whether a resource exists to an unauthorised caller.</li>
 * </ul>
 *
 * <p>WHY ordering matters: more specific handlers ({@code ApiException}, validation, security) are
 * declared before the catch-all {@code Exception} handler so the generic 500 only fires for truly
 * unexpected faults.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ResponseFactory responses;
    private final MessageResolver messages;

    /**
     * @param responses envelope builder.
     * @param messages  resolver for field-level validation messages (SW/EN).
     */
    public GlobalExceptionHandler(ResponseFactory responses, MessageResolver messages) {
        this.responses = responses;
        this.messages = messages;
    }

    /**
     * Handles all typed domain/application failures.
     *
     * @param ex the thrown {@link ApiException} carrying an {@link ErrorCode}.
     * @return the envelope at the error's mapped HTTP status.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleApiException(ApiException ex) {
        ErrorCode code = ex.getErrorCode();
        // 4xx are expected client errors → log at WARN without a stack trace; never leak args verbatim.
        log.warn("Domain error {}: {}", code.name(), ex.getMessage());
        // No field-level errors for a domain exception → pass null so data.errors is omitted; the
        // machine code is preserved at data.code by ResponseFactory.error (ADR-0008). The cast binds
        // to error(ErrorCode, List<ErrorDetail>, Object...) so the i18n messageArgs reach the message.
        return ResponseEntity.status(code.httpStatus())
                .body(responses.error(code, (List<ErrorDetail>) null, ex.getMessageArgs()));
    }

    /**
     * Handles Bean Validation failures on {@code @Valid} request bodies, collapsing each field error
     * into a localised {@link ErrorDetail} (PRD §17 field-level errors).
     *
     * @param ex the validation exception raised by Spring MVC.
     * @return a {@link ErrorCode#VALIDATION_FAILED} envelope whose {@code data} is an
     *         {@link ApiError} carrying the machine code and the field-level {@code errors[]}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleValidation(
            MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toDetail)
                .toList();
        // The collected field errors land in ApiError.errors (data.errors[]); the machine code
        // VALIDATION_FAILED lands in ApiError.code (data.code) — ADR-0008.
        ApiResponse<ApiError> body = responses.error(ErrorCode.VALIDATION_FAILED, details);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus()).body(body);
    }

    /**
     * Handles authorization denials from method security ({@code @PreAuthorize}).
     * Normalised to a generic {@link ErrorCode#FORBIDDEN} — the response must not disclose what was
     * being protected (PRD §18).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .body(responses.error(ErrorCode.FORBIDDEN, (List<ErrorDetail>) null));
    }

    /**
     * Handles missing/invalid authentication. Normalised to {@link ErrorCode#UNAUTHENTICATED}.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failure: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.httpStatus())
                .body(responses.error(ErrorCode.UNAUTHENTICATED, (List<ErrorDetail>) null));
    }

    /**
     * Handles optimistic-lock collisions (stale {@code @Version}) → {@link ErrorCode#CONFLICT}
     * (PRD §17 concurrency).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.CONFLICT.httpStatus())
                .body(responses.error(ErrorCode.CONFLICT, (List<ErrorDetail>) null));
    }

    /**
     * A request whose path matches no controller mapping (and no static resource) — return a clean
     * {@code 404} envelope instead of letting it fall through to the {@link #handleUnexpected catch-all},
     * which would mislabel an unknown URL as a {@code 500 INTERNAL_ERROR}. Spring 6.1 raises
     * {@link NoResourceFoundException} once an unmatched path reaches the resource handler.
     *
     * @param ex the no-resource exception (carries the unmatched path).
     * @return a {@link ErrorCode#NOT_FOUND} envelope (no internals leaked).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<ApiError>> handleNoResource(NoResourceFoundException ex) {
        log.warn("No handler/resource for path: {}", ex.getResourcePath());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(responses.error(ErrorCode.NOT_FOUND, (List<ErrorDetail>) null));
    }

    /**
     * Catch-all for unexpected faults. Returns a generic {@link ErrorCode#INTERNAL_ERROR} to the
     * client; the real exception (cause, stack) is logged server-side only and never serialised
     * (PRD §18 — no stack traces/PII to the client).
     *
     * @param ex the unexpected exception.
     * @return a generic 500 envelope.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ApiError>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(responses.error(ErrorCode.INTERNAL_ERROR, (List<ErrorDetail>) null));
    }

    /** Maps a Spring {@link FieldError} to a localised {@link ErrorDetail}. */
    private ErrorDetail toDetail(FieldError fieldError) {
        // fieldError.getCode() is the constraint name (e.g. "NotBlank") — a stable machine token.
        String resolved = fieldError.getDefaultMessage() != null
                ? messages.resolve(fieldError.getDefaultMessage())
                : fieldError.getDefaultMessage();
        return new ErrorDetail(fieldError.getField(), fieldError.getCode(), resolved);
    }
}
