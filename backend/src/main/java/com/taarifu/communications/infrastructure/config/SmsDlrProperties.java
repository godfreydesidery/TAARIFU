package com.taarifu.communications.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the inbound SMS <b>delivery-report (DLR) webhook</b>
 * ({@code POST /communications/sms/dlr}), bound from {@code taarifu.communications.sms.dlr.*}
 * (PRD §13 "logged with delivery status", EI-3 DLR webhook, §18).
 *
 * <p>Responsibility: carry (1) the <b>shared secret</b> the aggregator presents on each DLR callback so the
 * webhook can <b>authenticate</b> it (the {@code /ussd/gateway} / {@code /payments/webhook} shared-secret
 * precedent — the callback carries no user JWT), and (2) the request <b>field names</b> the aggregator uses
 * for the correlation reference and the delivery status, so the same handler fits aggregators with different
 * JSON key conventions without code change (DI1 — vendor quirks confined to config + adapter).</p>
 *
 * <p><b>WHY a separate record (not a field on the existing {@code Sms} config)</b>: the DLR is a distinct
 * <i>inbound</i> integration concern (its own auth secret + payload shape) from the <i>outbound</i> submit
 * settings; keeping it in its own {@code taarifu.communications.sms.dlr.*} namespace keeps each concern
 * single-responsibility (ISP) and lets the webhook bind only the slice it needs.</p>
 *
 * <p><b>🔒 Secret handling (CLAUDE.md §12, PRD §18):</b> {@link #secret} is env/secret-manager provided and
 * NEVER committed; it is compared in constant time and never logged. With <b>no secret configured the
 * webhook is fail-closed</b> — it authenticates nothing — so a misconfiguration can never silently accept a
 * forged delivery report (a forged DLR could only flip a notification's delivery status, but the platform
 * still treats inbound provider data as untrusted by default).</p>
 *
 * @param secret        the shared secret the aggregator must present (header {@link #header}); env-provided,
 *                      never committed. {@code null}/blank ⇒ the webhook is fail-closed (accepts nothing).
 * @param header        the HTTP header the aggregator presents the {@link #secret} in. Default
 *                      {@code X-DLR-Secret}; override to match the aggregator's scheme.
 * @param referenceField the JSON field name carrying the aggregator's echo of our submit {@code reference}
 *                      (= the dispatch row's idempotency key) — the sole correlator. Default {@code reference}.
 * @param statusField   the JSON field name carrying the delivery status string. Default {@code status}.
 * @param deliveredValue the {@link #statusField} value that means "delivered" (case-insensitive). Default
 *                      {@code DELIVERED}; any other non-blank status is treated as a terminal failure report.
 */
@ConfigurationProperties(prefix = "taarifu.communications.sms.dlr")
public record SmsDlrProperties(
        String secret,
        String header,
        String referenceField,
        String statusField,
        String deliveredValue
) {

    /** Default header the aggregator presents the shared secret in. */
    private static final String DEFAULT_HEADER = "X-DLR-Secret";
    /** Default JSON field carrying our echoed submit reference (the correlator). */
    private static final String DEFAULT_REFERENCE_FIELD = "reference";
    /** Default JSON field carrying the delivery status. */
    private static final String DEFAULT_STATUS_FIELD = "status";
    /** Default status value meaning "delivered". */
    private static final String DEFAULT_DELIVERED_VALUE = "DELIVERED";

    /** Normalises blank placeholders to {@code null}/defaults so an unset block still yields a valid record. */
    public SmsDlrProperties {
        secret = blankToNull(secret);
        header = (header == null || header.isBlank()) ? DEFAULT_HEADER : header;
        referenceField = (referenceField == null || referenceField.isBlank())
                ? DEFAULT_REFERENCE_FIELD : referenceField;
        statusField = (statusField == null || statusField.isBlank()) ? DEFAULT_STATUS_FIELD : statusField;
        deliveredValue = (deliveredValue == null || deliveredValue.isBlank())
                ? DEFAULT_DELIVERED_VALUE : deliveredValue;
    }

    /** @return {@code true} if a non-blank shared secret is configured (else the webhook is fail-closed). */
    public boolean hasSecret() {
        return secret != null;
    }

    /** @return {@code null} for a {@code null}/blank string, else the trimmed value. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
