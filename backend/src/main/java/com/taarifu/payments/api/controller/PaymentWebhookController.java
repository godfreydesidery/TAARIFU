package com.taarifu.payments.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.payments.application.service.ReconciliationService;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mobile-money aggregator callback (webhook) ingress — <b>HMAC-verified, fail-closed, idempotent on the
 * provider reference</b> (ADR-0015; PRD §23.5).
 *
 * <p>Responsibility: receives a settlement callback at {@code POST /payments/webhook/{provider}}, verifies
 * its HMAC signature over the <b>raw body</b> against the per-rail secret, and — only on a valid signature —
 * hands the parsed {@code (providerRef)} to {@link ReconciliationService}, which confirms settlement against
 * the provider before crediting the wallet (never trust-the-callback).</p>
 *
 * <p><b>Authentication model:</b> this endpoint carries <b>no user JWT</b> — the aggregator is authenticated
 * by the HMAC of the body (the {@code /ussd/gateway} shared-secret precedent). It therefore has no
 * {@code @PreAuthorize}; the signature check is the authentication, and it is <b>fail-closed</b>: an invalid
 * or missing signature returns a benign accepted-but-ignored response with <b>no state change</b> and no
 * reason disclosed (no forgery oracle).</p>
 *
 * <p><b>CENTRAL NEED:</b> {@code common.security.SecurityConfig} must add {@code POST /payments/webhook/**}
 * to its {@code PUBLIC_POST_PATTERNS} (the {@code /ussd/gateway} precedent) so the aggregator can reach this
 * URL without a user token. Until then the endpoint is HMAC-secured in code but unreachable anonymously.</p>
 *
 * <p><b>Idempotency / replay (PRD §23.5):</b> reconciliation is idempotent on {@code (provider, provider_ref)}
 * and on the per-top-up credit key, so a duplicate or out-of-order callback credits the wallet exactly once.
 * The response is always a benign 200 envelope (the aggregator must not be encouraged to retry-storm on a
 * 4xx/5xx for an event we have already handled).</p>
 *
 * <p><b>Privacy (PRD §18):</b> the raw callback body and any MSISDN it carries are never logged; only the
 * rail and a verified/ignored outcome are logged.</p>
 */
@RestController
@RequestMapping(path = "/payments/webhook")
@Tag(name = "Payment webhook", description = "Mobile-money settlement callbacks (HMAC-verified, idempotent).")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final MobileMoneyGateway gateway;
    private final ReconciliationService reconciliation;
    private final ResponseFactory responses;

    /**
     * @param gateway        the active rail (signature verification + callback parsing).
     * @param reconciliation the idempotent, provider-verified settlement → credit path.
     * @param responses      envelope builder.
     */
    public PaymentWebhookController(MobileMoneyGateway gateway,
                                    ReconciliationService reconciliation,
                                    ResponseFactory responses) {
        this.gateway = gateway;
        this.reconciliation = reconciliation;
        this.responses = responses;
    }

    /**
     * Handles one settlement callback for a rail.
     *
     * @param provider    the path-segment rail (e.g. {@code MPESA}); a value outside the enum yields a 400
     *                    via the framework's enum binding (an unknown rail cannot settle anything).
     * @param signature   the HMAC signature header presented by the aggregator (provider-specific header,
     *                    bound by name here for portability).
     * @param rawBody     the exact request body bytes (HMAC is computed over these — see the class Javadoc).
     * @return a benign accepted envelope in all cases (verified-and-reconciled, or ignored); the body never
     *         discloses whether the signature was valid (no oracle).
     */
    @PostMapping("/{provider}")
    @Operation(summary = "Mobile-money settlement callback",
            description = "Verifies the HMAC over the raw body, then reconciles idempotently and credits the "
                    + "wallet only after provider-confirmed settlement. No user token; authenticated by HMAC.")
    public ApiResponse<Void> handle(
            @PathVariable MobileMoneyProvider provider,
            @RequestHeader(name = "X-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] rawBody) {

        // Fail-closed: verify the HMAC over the EXACT bytes before any state change. An invalid/missing
        // signature is silently ignored (benign 200, no reason disclosed) — a forged callback achieves
        // nothing and learns nothing (PRD §23.5).
        if (rawBody == null || !gateway.verifyCallbackSignature(rawBody, signature)) {
            log.warn("Payment webhook rejected: provider={}, reason=SIGNATURE_INVALID", provider);
            return responses.ok(null);
        }

        MobileMoneyGateway.CallbackResult parsed = gateway.parseCallback(rawBody);
        // reconcile() is idempotent and confirms settlement against the provider before crediting.
        reconciliation.reconcile(provider, parsed.providerRef());
        log.info("Payment webhook accepted: provider={}", provider);
        return responses.ok(null);
    }
}
