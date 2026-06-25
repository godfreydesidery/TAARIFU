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
 * Production {@link MobileMoneyGateway} for <b>HaloPesa (Halotel)</b> (ADR-0015; PRD §23.6, §21 EI-20).
 * Selected by {@code taarifu.payments.gateway.provider=halopesa}; endpoint + HMAC secret from the environment.
 *
 * <p>Responsibility: encodes the <b>real HaloPesa push shape</b> over
 * {@link AbstractHmacMobileMoneyGateway}'s shared HMAC/transport machinery. HaloPesa's collection API uses an
 * {@code externalId} correlation handle and a numeric {@code responseCode}/{@code resultCode}
 * ({@code "000"}/{@code 0} == success):</p>
 * <ul>
 *   <li><b>Collection</b> {@code POST {base}/api/PushPayment} with {@code externalId} (our idempotency key),
 *       {@code msisdn}, {@code amount}, {@code currency} and the env-bound {@code businessNumber}.</li>
 *   <li><b>Callback</b> carries {@code {externalId, responseCode}} (or {@code resultCode}); a code of
 *       {@code "000"} / {@code 0} is success. The reference is {@code externalId} (or {@code transactionId}).</li>
 *   <li><b>Status query</b> {@code GET {base}/api/PushPayment/{externalId}} returning the same
 *       {@code responseCode} — settlement confirmed only on a success code (never trust the callback,
 *       PRD §23.5).</li>
 * </ul>
 *
 * <p>Vendor specifics live <b>only</b> here (DI1). Secrets are env-bound; the MSISDN is never logged in full
 * (PRD §18).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "halopesa")
public class HaloPesaGateway extends AbstractHmacMobileMoneyGateway {

    private static final String PUSH_PATH = "/api/PushPayment";
    /** HaloPesa success response code (the ISO-8583-style "approved" code). */
    private static final String SUCCESS_CODE = "000";

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public HaloPesaGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** Test seam: inject a mock-transport {@link RestClient}. */
    HaloPesaGateway(PaymentsGatewayProperties config, RestClient restClient) {
        super(config, restClient);
    }

    /** @return {@link MobileMoneyProvider#HALOPESA}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.HALOPESA;
    }

    /** HaloPesa push-payment submit path. */
    @Override
    protected String collectionPath() {
        return PUSH_PATH;
    }

    /** Builds the HaloPesa push-payment request body ({@code externalId/msisdn/amount/businessNumber}). */
    @Override
    protected Map<String, Object> buildCollectionBody(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalId", request.idempotencyKey());
        body.put("msisdn", request.payerMsisdn());
        body.put("amount", request.amountMinor());
        body.put("currency", request.currency());
        body.put("businessNumber", config.merchantId());
        return body;
    }

    /** HaloPesa returns a {@code {transactionId}}/{@code {externalId}} envelope; fall back to our key. */
    @Override
    protected String extractProviderRef(String responseBody, String fallbackRef) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallbackRef;
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            String ref = firstText(root, "transactionId", "externalId", "reference");
            return ref != null ? ref : super.extractProviderRef(responseBody, fallbackRef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return responseBody.strip();
        }
    }

    /** Parses the HaloPesa callback {@code {externalId, responseCode}} ({@code "000"}/{@code 0} == settled). */
    @Override
    protected CallbackResult parseCallbackBody(JsonNode root) {
        String ref = firstText(root, "externalId", "transactionId", "reference");
        boolean settled = isSuccessCode(root);
        return new CallbackResult(ref, settled);
    }

    /** HaloPesa status query keyed on the {@code externalId}. */
    @Override
    protected String statusPath(String providerRef) {
        return PUSH_PATH + "/" + providerRef;
    }

    /** Settlement confirmed only when the status query reports a HaloPesa success code. */
    @Override
    protected boolean parseStatus(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            return isSuccessCode(JSON.readTree(responseBody.strip()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return false;
        }
    }

    /**
     * Reads the HaloPesa success convention: a textual {@code "000"} or numeric {@code 0} on
     * {@code responseCode}/{@code resultCode}/{@code code}.
     */
    private static boolean isSuccessCode(JsonNode root) {
        for (String field : new String[]{"responseCode", "resultCode", "code"}) {
            JsonNode node = root.get(field);
            if (node == null) {
                continue;
            }
            if (node.isNumber()) {
                return node.asInt() == 0;
            }
            if (node.isTextual()) {
                String value = node.asText().strip();
                return SUCCESS_CODE.equals(value) || "0".equals(value);
            }
        }
        return false;
    }
}
