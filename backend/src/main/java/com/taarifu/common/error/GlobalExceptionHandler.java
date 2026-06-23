package com.taarifu.common.error;

import com.taarifu.common.api.ResponseFactory;
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
    public ResponseEntity<ApiResponse<Object>> handleApiException(ApiException ex) {
        ErrorCode code = ex.getErrorCode();
        // 4xx are expected client errors → log at WARN without a stack trace; never leak args verbatim.
        log.warn("Domain error {}: {}", code.name(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus())
                .body(responses.error(code, null, ex.getMessageArgs()));
    }

    /**
     * Handles Bean Validation failures on {@code @Valid} request bodies, collapsing each field error
     * into a localised {@link ErrorDetail} (PRD §17 field-level errors).
     *
     * @param ex the validation exception raised by Spring MVC.
     * @return a {@link ErrorCode#VALIDATION_FAILED} envelope with {@code data.errors[]}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorDetail.ValidationErrors>> handleValidation(
            MethodArgumentNotValidException ex) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toDetail)
                .toList();
        ApiResponse<ErrorDetail.ValidationErrors> body = responses.error(
                ErrorCode.VALIDATION_FAILED, new ErrorDetail.ValidationErrors(details));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus()).body(body);
    }

    /**
     * Handles authorization denials from method security ({@code @PreAuthorize}).
     * Normalised to a generic {@link ErrorCode#FORBIDDEN} — the response must not disclose what was
     * being protected (PRD §18).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .body(responses.error(ErrorCode.FORBIDDEN, null));
    }

    /**
     * Handles missing/invalid authentication. Normalised to {@link ErrorCode#UNAUTHENTICATED}.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failure: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.httpStatus())
                .body(responses.error(ErrorCode.UNAUTHENTICATED, null));
    }

    /**
     * Handles optimistic-lock collisions (stale {@code @Version}) → {@link ErrorCode#CONFLICT}
     * (PRD §17 concurrency).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.CONFLICT.httpStatus())
                .body(responses.error(ErrorCode.CONFLICT, null));
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
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(responses.error(ErrorCode.INTERNAL_ERROR, null));
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
