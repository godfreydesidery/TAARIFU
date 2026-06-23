package com.taarifu.common.error;

/**
 * Base unchecked exception for all expected domain/application failures (ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: carries an {@link ErrorCode} (which fixes the HTTP status + machine code +
 * i18n key) plus optional positional message arguments, so the
 * {@link GlobalExceptionHandler} can translate any thrown {@code ApiException} into the single
 * {@code ApiResponse} envelope with the correct status and a <b>localised</b> message — without the
 * domain ever touching HTTP or i18n concerns (clean boundaries, CLAUDE.md §3).</p>
 *
 * <p>WHY unchecked (extends {@link RuntimeException}): domain/service code stays uncluttered by
 * {@code throws} declarations; the centralised advice is the single catch point (ADR-0008). Specific
 * failures subclass this (e.g. {@link ResourceNotFoundException}) for intent-revealing call sites.</p>
 */
public class ApiException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Object[] messageArgs;

    /**
     * @param errorCode   the typed error (status + code + i18n key).
     * @param messageArgs positional arguments for the i18n message template (may be empty).
     */
    public ApiException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.messageArgs = messageArgs;
    }

    /**
     * @param errorCode   the typed error.
     * @param cause       the underlying cause (preserved for logs; never serialised to the client).
     * @param messageArgs positional i18n arguments.
     */
    public ApiException(ErrorCode errorCode, Throwable cause, Object... messageArgs) {
        super(errorCode.name(), cause);
        this.errorCode = errorCode;
        this.messageArgs = messageArgs;
    }

    /** @return the typed error code driving status/message resolution. */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** @return the positional i18n message arguments (possibly empty, never {@code null}). */
    public Object[] getMessageArgs() {
        return messageArgs == null ? new Object[0] : messageArgs.clone();
    }
}
