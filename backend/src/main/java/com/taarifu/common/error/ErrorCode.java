package com.taarifu.common.error;

import org.springframework.http.HttpStatus;

/**
 * The catalogue of <b>stable machine error codes</b> mapped to an HTTP status and an i18n message
 * key (PRD §17, ADR-0008, ARCHITECTURE.md §5.2).
 *
 * <p>Responsibility: one authoritative table so (a) clients branch on a language-independent
 * {@code code}, (b) the {@link GlobalExceptionHandler} returns the correct HTTP status, and
 * (c) the human {@code message} resolves localised (SW/EN) from {@link #messageKey()} via
 * {@code MessageResolver} (ADR-0010).</p>
 *
 * <p>WHY an enum (not scattered string constants): it makes the full error surface reviewable in one
 * place and impossible to misspell at a call site (DRY, CLAUDE.md §3). Codes are append-only; never
 * repurpose an existing code's meaning (clients depend on it).</p>
 *
 * <p>Tanzanian/integrity-specific codes ({@code TIER_TOO_LOW}, {@code OUT_OF_SCOPE},
 * {@code CONFLICT_OF_INTEREST}, {@code DUPLICATE_IDENTITY}) are defined now so the security and
 * reporting increments throw a typed, localised error rather than a generic 403/409 (PRD §7, §18,
 * D13/D15/D16).</p>
 */
public enum ErrorCode {

    /** Success sentinel — used by {@code ResponseFactory.ok()}; not thrown. */
    OK(HttpStatus.OK, "common.ok"),

    /** Bean Validation / malformed-request failure; carries field-level {@code errors[]}. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "common.validationFailed"),

    /** Request body/parameters could not be parsed or are structurally invalid. */
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "common.badRequest"),

    /** No authenticated principal where one is required. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "common.unauthenticated"),

    /** Authenticated but not permitted (RBAC/method-security denial). */
    FORBIDDEN(HttpStatus.FORBIDDEN, "common.forbidden"),

    /** Authenticated principal's trust tier is below the action's requirement (PRD §7.3, D13). */
    TIER_TOO_LOW(HttpStatus.FORBIDDEN, "common.tierTooLow"),

    /** Action attempted outside the principal's electoral/area/category scope (PRD §7.1, §9.0). */
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "common.outOfScope"),

    /** Self-action blocked (rate/answer/resolve/moderate self or own work — D16). */
    CONFLICT_OF_INTEREST(HttpStatus.FORBIDDEN, "common.conflictOfInterest"),

    /** Requested resource does not exist (or is soft-deleted/out of the caller's view). */
    NOT_FOUND(HttpStatus.NOT_FOUND, "common.notFound"),

    /** Optimistic-lock or uniqueness/state conflict (PRD §17 concurrency). */
    CONFLICT(HttpStatus.CONFLICT, "common.conflict"),

    /** National/voter-ID dedup violation — one person, one account (PRD §6.4, D15). */
    DUPLICATE_IDENTITY(HttpStatus.CONFLICT, "common.duplicateIdentity"),

    /**
     * A staff account (MODERATOR/ADMIN/ROOT) must complete a TOTP second factor before holding a staff
     * session or reaching a staff endpoint (N-4). Surfaced when login cannot complete without TOTP and
     * the account has not yet enrolled, directing it to {@code /auth/mfa/totp/setup} → {@code activate}.
     */
    MFA_REQUIRED(HttpStatus.FORBIDDEN, "common.mfaRequired"),

    /** Rate/velocity/quota limit exceeded (PRD §18, §23 free-quota). */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "common.rateLimited"),

    /** Unhandled server fault — message is generic; details are logged (never leaked, PRD §18). */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "common.internalError"),

    /** A required downstream/integration is unavailable and no degraded path applies (PRD §21). */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "common.serviceUnavailable");

    private final HttpStatus httpStatus;
    private final String messageKey;

    ErrorCode(HttpStatus httpStatus, String messageKey) {
        this.httpStatus = httpStatus;
        this.messageKey = messageKey;
    }

    /** @return the HTTP status this error maps to. */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** @return the i18n key resolved to the localised {@code ApiResponse.message}. */
    public String messageKey() {
        return messageKey;
    }
}
