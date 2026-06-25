package com.taarifu.payments.infrastructure.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Production {@link MobileMoneyGateway} for <b>Tigo Pesa (Mixx by Yas)</b> (ADR-0015; PRD §23.6, §21 EI-20).
 * Selected by {@code taarifu.payments.gateway.provider=tigopesa}; endpoint + HMAC secret from the environment.
 *
 * <p>Responsibility: encodes the <b>real Mixx push-bill shape</b> over
 * {@link AbstractHmacMobileMoneyGateway}'s shared HMAC/transport machinery:</p>
 * <ul>
 *   <li><b>Collection (push-to-pay)</b> {@code POST {base}/push-billpay} with Mixx fields —
 *       {@code referenceId} (our idempotency key), {@code msisdn}, {@code amount}, and the env-bound
 *       {@code merchantAccountNumber}.</li>
 *   <li><b>Callback</b> carries {@code {referenceId, status}} where {@code status} is {@code SUCCESS} or
 *       {@code FAILED}; the reference is {@code referenceId} (or {@code transactionReference}).</li>
 *   <li><b>Status query</b> {@code GET {base}/push-billpay/{referenceId}} returning the same
 *       {@code status} — settlement is confirmed only on {@code SUCCESS} (never trust the callback,
 *       PRD §23.5).</li>
 * </ul>
 *
 * <p>Vendor specifics live <b>only</b> here (DI1). Secrets are env-bound; the MSISDN is never logged in full
 * (PRD §18).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "tigopesa")
public class TigoPesaGateway extends AbstractHmacMobileMoneyGateway {

    private static final String PUSH_PATH = "/push-billpay";

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public TigoPesaGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** Test seam: inject a mock-transport {@link RestClient}. */
    TigoPesaGateway(PaymentsGatewayProperties config, RestClient restClient) {
        super(config, restClient);
    }

    /** @return {@link MobileMoneyProvider#TIGOPESA}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.TIGOPESA;
    }

    /** Mixx push-billpay submit path. */
    @Override
    protected String collectionPath() {
        return PUSH_PATH;
    }

    /** Builds the Mixx push-billpay request body ({@code referenceId/msisdn/amount/merchantAccountNumber}). */
    @Override
    protected Map<String, Object> buildCollectionBody(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceId", request.idempotencyKey());
        body.put("msisdn", request.payerMsisdn());
        body.put("amount", request.amountMinor());
        body.put("currency", request.currency());
        body.put("merchantAccountNumber", config.merchantId());
        return body;
    }

    /** Mixx returns a {@code {referenceId}} envelope; fall back to the idempotency key if absent. */
    @Override
    protected String extractProviderRef(String responseBody, String fallbackRef) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallbackRef;
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            String ref = firstText(root, "referenceId", "transactionReference", "transactionId");
            return ref != null ? ref : super.extractProviderRef(responseBody, fallbackRef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return responseBody.strip();
        }
    }

    /** Parses the Mixx callback {@code {referenceId, status}} (status {@code SUCCESS} == settled). */
    @Override
    protected CallbackResult parseCallbackBody(JsonNode root) {
        String ref = firstText(root, "referenceId", "transactionReference", "transactionId");
        String status = firstText(root, "status", "state");
        boolean settled = "SUCCESS".equalsIgnoreCase(status) || "SUCCESSFUL".equalsIgnoreCase(status);
        return new CallbackResult(ref, settled);
    }

    /** Mixx status query keyed on the {@code referenceId}. */
    @Override
    protected String statusPath(String providerRef) {
        return PUSH_PATH + "/" + providerRef;
    }

    /** Settlement confirmed only when the status query reports {@code SUCCESS}. */
    @Override
    protected boolean parseStatus(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(responseBody.strip());
            String status = firstText(root, "status", "state");
            return "SUCCESS".equalsIgnoreCase(status) || "SUCCESSFUL".equalsIgnoreCase(status);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return "true".equalsIgnoreCase(responseBody.strip());
        }
    }
}
