package com.taarifu.communications.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.communications.application.service.SmsDeliveryReportService;
import com.taarifu.communications.infrastructure.config.SmsDlrProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The SMS aggregator <b>delivery-report (DLR) callback</b> ingress — <b>shared-secret authenticated,
 * fail-closed, idempotent on the correlation reference</b> (PRD §13 "logged with delivery status", EI-3,
 * §18; the {@code /payments/webhook} + {@code /ussd/gateway} precedent).
 *
 * <p>Responsibility: receive a delivery receipt at {@code POST /communications/sms/dlr}, authenticate the
 * aggregator by a constant-time comparison of a shared secret it presents on a header, parse the
 * {@code (reference, status)} pair, and — only on a valid secret — hand it to
 * {@link SmsDeliveryReportService}, which idempotently advances the correlated {@code Notification}'s
 * delivery state ({@code → DELIVERED}/{@code → FAILED}). The aggregator's DLR JSON quirks (which field
 * carries the reference/status) are confined to {@link SmsDlrProperties} so the same handler fits different
 * aggregators (DI1).</p>
 *
 * <p><b>Authentication model:</b> this endpoint carries <b>no user JWT</b> — the aggregator is authenticated
 * by a shared secret on the configured header (the {@code /ussd/gateway} shared-secret precedent). It
 * therefore has no {@code @PreAuthorize}; the secret check IS the authentication, and it is
 * <b>fail-closed</b>: a missing/blank/mismatched secret — or <b>no secret configured at all</b> — returns a
 * benign accepted-but-ignored response with <b>no state change</b> and no reason disclosed (no forgery
 * oracle). The comparison is constant-time ({@link MessageDigest#isEqual}) so it leaks no timing signal.</p>
 *
 * <p><b>CENTRAL NEED:</b> {@code common.security.SecurityConfig} must add {@code POST /communications/sms/dlr}
 * to its {@code PUBLIC_POST_PATTERNS} (the {@code /ussd/gateway} + {@code /payments/webhook/**} precedent) so
 * the aggregator can reach this URL without a user token. Until then the endpoint is secret-secured in code
 * but unreachable anonymously (a 401 from the security filter). This controller and its secret check are the
 * complete authentication; the central change is purely the route allow-list entry.</p>
 *
 * <p><b>Idempotency / replay (DI4):</b> {@link SmsDeliveryReportService#apply} is idempotent and
 * non-regressing on {@code reference} — a duplicate, out-of-order, or stale DLR advances the row at most once
 * and never downgrades a better outcome. The response is always a benign 200 envelope so the aggregator is
 * not encouraged to retry-storm on a 4xx/5xx for an event we have already handled.</p>
 *
 * <p><b>🔒 Privacy (PRD §18, S-4):</b> a DLR carries no MSISDN to us (correlation is by the opaque
 * {@code reference}); the raw body and any provider text are never logged — only the rail tag and a
 * verified/ignored outcome.</p>
 */
@RestController
@RequestMapping(path = "/communications/sms/dlr")
@Tag(name = "SMS delivery report",
        description = "SMS aggregator delivery-report callbacks (shared-secret authenticated, idempotent).")
public class SmsDeliveryReportController {

    private static final Logger log = LoggerFactory.getLogger(SmsDeliveryReportController.class);

    private final SmsDeliveryReportService dlrService;
    private final SmsDlrProperties properties;
    private final ObjectMapper objectMapper;
    private final ResponseFactory responses;

    /**
     * @param dlrService   the idempotent, non-regressing DLR → notification-state path.
     * @param properties   the DLR webhook's shared secret + header + payload field names.
     * @param objectMapper the shared Jackson mapper (parses the callback JSON; never logs it).
     * @param responses    the single-envelope builder (ARCHITECTURE §5.1).
     */
    public SmsDeliveryReportController(SmsDeliveryReportService dlrService,
                                       SmsDlrProperties properties,
                                       ObjectMapper objectMapper,
                                       ResponseFactory responses) {
        this.dlrService = dlrService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responses = responses;
    }

    /**
     * Handles one SMS delivery-report callback.
     *
     * @param presentedSecret the shared secret the aggregator presents on the configured header (bound by
     *                        name for portability); required-false so a missing header is a benign reject.
     * @param rawBody         the callback body bytes (parsed as JSON; never logged — may echo a recipient).
     * @return a benign accepted envelope in all cases (authenticated-and-applied, or ignored); the body
     *         never discloses whether the secret was valid or whether a row matched (no oracle).
     */
    @PostMapping
    @Operation(summary = "SMS delivery-report callback",
            description = "Authenticates the aggregator by a shared secret, then idempotently advances the "
                    + "correlated notification's delivery status. No user token; authenticated by the secret.")
    public ApiResponse<Void> handle(
            @RequestHeader(name = "X-DLR-Secret", required = false) String presentedSecret,
            @RequestBody(required = false) byte[] rawBody) {

        // Fail-closed: authenticate the shared secret before any state change. A missing/mismatched secret —
        // or no secret configured — is silently ignored (benign 200, no reason disclosed) so a forged DLR
        // achieves nothing and learns nothing (PRD §18; the /payments/webhook precedent).
        if (rawBody == null || !secretMatches(presentedSecret)) {
            log.warn("SMS DLR rejected: reason=SECRET_INVALID");
            return responses.ok(null);
        }

        Parsed parsed = parse(rawBody);
        if (parsed == null) {
            // Unparseable/empty body → benign ignore (never throw a 5xx that triggers an aggregator retry-storm).
            log.warn("SMS DLR ignored: reason=UNPARSEABLE_BODY");
            return responses.ok(null);
        }
        dlrService.apply(parsed.reference(), parsed.delivered(), parsed.reason());
        return responses.ok(null);
    }

    /**
     * Constant-time check that the presented secret matches the configured one. Fail-closed: returns
     * {@code false} when no secret is configured or none is presented, so a misconfiguration never opens the
     * endpoint.
     *
     * @param presented the secret the caller presented (may be {@code null}).
     * @return {@code true} only if a secret is configured, one was presented, and they are equal.
     */
    private boolean secretMatches(String presented) {
        if (!properties.hasSecret() || presented == null || presented.isBlank()) {
            return false;
        }
        // Constant-time comparison so the endpoint leaks no timing signal about the secret (PRD §18).
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses the aggregator's DLR JSON into the provider-neutral {@link Parsed} triple, using the configured
     * field names. Returns {@code null} on an unparseable/empty body (the caller benign-ignores it).
     *
     * @param rawBody the callback body bytes.
     * @return the parsed report, or {@code null} if the body is not usable JSON.
     */
    private Parsed parse(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || root.isMissingNode()) {
                return null;
            }
            String reference = text(root, properties.referenceField());
            String status = text(root, properties.statusField());
            boolean delivered = status != null
                    && status.strip().equalsIgnoreCase(properties.deliveredValue());
            // On a non-delivered status, pass the raw status string as the (already non-PII) failure reason.
            String reason = delivered ? null : status;
            return new Parsed(reference, delivered, reason);
        } catch (java.io.IOException | RuntimeException ex) {
            // Never log the body (it may echo the MSISDN); the class name is enough to triage (PRD §18).
            log.warn("SMS DLR parse failed: reason={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** @return the trimmed text value at {@code field}, or {@code null} if absent/non-textual. */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return (node.isMissingNode() || node.isNull()) ? null : node.asText(null);
    }

    /**
     * The provider-neutral parsed delivery report.
     *
     * @param reference the echoed submit reference (= our dispatch idempotency key) — the correlator.
     * @param delivered whether the report indicates successful delivery.
     * @param reason    a non-PII provider status/reason for a non-delivered report, or {@code null}.
     */
    private record Parsed(String reference, boolean delivered, String reason) {
    }
}
