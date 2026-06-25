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
 * Production {@link MobileMoneyGateway} for <b>Vodacom M-Pesa (Tanzania)</b> — a Safaricom/Vodacom
 * <b>Daraja-style STK push</b> rail (ADR-0015; PRD §23.6, §21 EI-20). Selected by
 * {@code taarifu.payments.gateway.provider=mpesa}; endpoint, HMAC secret and {@code BusinessShortCode}
 * (merchant id) come from the environment.
 *
 * <p>Responsibility: encodes the <b>real M-Pesa shape</b> over {@link AbstractHmacMobileMoneyGateway}'s shared
 * HMAC/transport machinery:</p>
 * <ul>
 *   <li><b>Collection (STK push)</b> {@code POST {base}/mpesa/stkpush/v1/processrequest} with the Daraja
 *       fields — {@code BusinessShortCode}, {@code Amount}, {@code PartyA}/{@code PhoneNumber},
 *       {@code AccountReference} (our idempotency key), {@code TransactionType=CustomerPayBillOnline}.</li>
 *   <li><b>Callback</b> is the nested Daraja body {@code Body.stkCallback.{CheckoutRequestID, ResultCode}};
 *       {@code ResultCode == 0} is success (Daraja convention). The reference is the
 *       {@code CheckoutRequestID}.</li>
 *   <li><b>Status query</b> {@code GET {base}/mpesa/stkpushquery/v1/query/{CheckoutRequestID}}; settlement is
 *       confirmed only when the query reports {@code ResultCode == 0} (never trust the callback — PRD
 *       §23.5).</li>
 * </ul>
 *
 * <p>Vendor specifics live <b>only</b> here (DI1). Secrets are env-bound; the MSISDN is sent to the rail but
 * never logged in full (PRD §18).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "mpesa")
public class MpesaGateway extends AbstractHmacMobileMoneyGateway {

    private static final String STK_PUSH_PATH = "/mpesa/stkpush/v1/processrequest";
    private static final String STK_QUERY_PATH = "/mpesa/stkpushquery/v1/query/";

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public MpesaGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** Test seam: inject a mock-transport {@link RestClient} so the request shape is asserted with no network. */
    MpesaGateway(PaymentsGatewayProperties config, RestClient restClient) {
        super(config, restClient);
    }

    /** @return {@link MobileMoneyProvider#MPESA}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.MPESA;
    }

    /** Daraja STK-push submit path. */
    @Override
    protected String collectionPath() {
        return STK_PUSH_PATH;
    }

    /**
     * Builds the Daraja STK-push request body. The {@code AccountReference} carries our idempotency key so the
     * rail can dedup a replayed push; {@code BusinessShortCode} is the env-bound merchant short-code.
     */
    @Override
    protected Map<String, Object> buildCollectionBody(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", config.merchantId());
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", request.amountMinor());
        body.put("PartyA", request.payerMsisdn());
        body.put("PhoneNumber", request.payerMsisdn());
        body.put("AccountReference", request.idempotencyKey());
        body.put("TransactionDesc", "Taarifu token top-up");
        return body;
    }

    /**
     * Daraja echoes a JSON envelope carrying the {@code CheckoutRequestID} — the correlation handle for the
     * later callback/query. Falls back to the idempotency key only if the rail returns nothing usable.
     */
    @Override
    protected String extractProviderRef(String responseBody, String fallbackRef) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallbackRef;
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            String ref = firstText(root, "CheckoutRequestID", "checkoutRequestID");
            return ref != null ? ref : super.extractProviderRef(responseBody, fallbackRef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            // A bare reference (sandbox harness) — use it verbatim.
            return responseBody.strip();
        }
    }

    /**
     * Parses the nested Daraja callback {@code Body.stkCallback.{CheckoutRequestID, ResultCode}}.
     * {@code ResultCode == 0} is the Daraja success convention; the {@code CheckoutRequestID} is the reference.
     */
    @Override
    protected CallbackResult parseCallbackBody(JsonNode root) {
        JsonNode stk = root.path("Body").path("stkCallback");
        if (stk.isMissingNode() || stk.isNull()) {
            // Tolerate a flattened sandbox callback that already carries the fields at the root.
            return super.parseCallbackBody(root);
        }
        String ref = firstText(stk, "CheckoutRequestID", "checkoutRequestID");
        JsonNode resultCode = stk.get("ResultCode");
        boolean settled = resultCode != null && resultCode.isNumber() && resultCode.asInt() == 0;
        return new CallbackResult(ref, settled);
    }

    /** Daraja STK-push status query keyed on the {@code CheckoutRequestID}. */
    @Override
    protected String statusPath(String providerRef) {
        return STK_QUERY_PATH + providerRef;
    }

    /**
     * Confirms settlement from the Daraja query response: {@code ResultCode == 0} (success). A bare boolean
     * {@code true} (sandbox harness) is also accepted.
     */
    @Override
    protected boolean parseStatus(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        String trimmed = responseBody.strip();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        try {
            JsonNode root = JSON.readTree(trimmed);
            JsonNode resultCode = root.get("ResultCode");
            if (resultCode != null && resultCode.isNumber()) {
                return resultCode.asInt() == 0;
            }
            return genericClaimedSettled(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return false;
        }
    }
}
