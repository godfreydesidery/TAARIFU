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
 * Production {@link MobileMoneyGateway} for <b>Airtel Money</b> (ADR-0015; PRD §23.6, §21 EI-20). Selected by
 * {@code taarifu.payments.gateway.provider=airtelmoney}; endpoint + HMAC secret from the environment.
 *
 * <p>Responsibility: encodes the <b>real Airtel collection shape</b> over
 * {@link AbstractHmacMobileMoneyGateway}'s shared HMAC/transport machinery. Airtel's Open API uses a
 * <b>nested</b> request/callback structure and a two-letter transaction status code:</p>
 * <ul>
 *   <li><b>Collection</b> {@code POST {base}/merchant/v1/payments/} with nested
 *       {@code subscriber.{country, currency, msisdn}} and
 *       {@code transaction.{amount, country, currency, id}} (our idempotency key as {@code transaction.id}).</li>
 *   <li><b>Callback</b> carries {@code transaction.{id, status.code}} where {@code status.code == "TS"} is a
 *       successful transaction and {@code "TF"} a failure; the reference is {@code transaction.id}.</li>
 *   <li><b>Status query</b> {@code GET {base}/standard/v1/payments/{transactionId}} returning the same
 *       {@code status.code} — settlement confirmed only on {@code TS} (never trust the callback,
 *       PRD §23.5).</li>
 * </ul>
 *
 * <p>Vendor specifics live <b>only</b> here (DI1). Secrets are env-bound; the MSISDN is never logged in full
 * (PRD §18).</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.payments.gateway.provider", havingValue = "airtelmoney")
public class AirtelMoneyGateway extends AbstractHmacMobileMoneyGateway {

    private static final String COLLECTION_PATH = "/merchant/v1/payments/";
    private static final String STATUS_PATH_PREFIX = "/standard/v1/payments/";
    private static final String TRANSACTION = "transaction";
    /** Airtel "Transaction Success" status code. */
    private static final String STATUS_SUCCESS = "TS";

    /**
     * @param config bound gateway settings (base URL + HMAC secret from env; fail-fast if blank).
     */
    public AirtelMoneyGateway(PaymentsGatewayProperties config) {
        super(config);
    }

    /** Test seam: inject a mock-transport {@link RestClient}. */
    AirtelMoneyGateway(PaymentsGatewayProperties config, RestClient restClient) {
        super(config, restClient);
    }

    /** @return {@link MobileMoneyProvider#AIRTELMONEY}. */
    @Override
    public MobileMoneyProvider provider() {
        return MobileMoneyProvider.AIRTELMONEY;
    }

    /** Airtel merchant-payments submit path. */
    @Override
    protected String collectionPath() {
        return COLLECTION_PATH;
    }

    /** Builds Airtel's nested {@code subscriber}/{@code transaction} request body. */
    @Override
    protected Map<String, Object> buildCollectionBody(CollectionRequest request) {
        Map<String, Object> subscriber = new LinkedHashMap<>();
        subscriber.put("country", "TZ");
        subscriber.put("currency", request.currency());
        subscriber.put("msisdn", request.payerMsisdn());

        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("amount", request.amountMinor());
        transaction.put("country", "TZ");
        transaction.put("currency", request.currency());
        transaction.put("id", request.idempotencyKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", request.idempotencyKey());
        body.put("subscriber", subscriber);
        body.put(TRANSACTION, transaction);
        return body;
    }

    /** Airtel echoes {@code data.transaction.id}; fall back to our idempotency key if absent. */
    @Override
    protected String extractProviderRef(String responseBody, String fallbackRef) {
        if (responseBody == null || responseBody.isBlank()) {
            return fallbackRef;
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode txn = root.path("data").path(TRANSACTION);
            String ref = firstText(txn.isMissingNode() ? root.path(TRANSACTION) : txn, "id", "airtel_money_id");
            return ref != null ? ref : super.extractProviderRef(responseBody, fallbackRef);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return responseBody.strip();
        }
    }

    /**
     * Parses Airtel's nested callback {@code transaction.{id, status.code}}; {@code status.code == "TS"} is a
     * settled transaction. Tolerates a {@code data.transaction} wrapper and a flattened sandbox callback.
     */
    @Override
    protected CallbackResult parseCallbackBody(JsonNode root) {
        JsonNode txn = root.path("data").path(TRANSACTION);
        if (txn.isMissingNode() || txn.isNull()) {
            txn = root.path(TRANSACTION);
        }
        if (txn.isMissingNode() || txn.isNull()) {
            return super.parseCallbackBody(root); // flattened sandbox shape
        }
        String ref = firstText(txn, "id", "airtel_money_id");
        String code = statusCode(txn);
        boolean settled = STATUS_SUCCESS.equalsIgnoreCase(code);
        return new CallbackResult(ref, settled);
    }

    /** Airtel transaction-enquiry path keyed on the {@code transaction.id}. */
    @Override
    protected String statusPath(String providerRef) {
        return STATUS_PATH_PREFIX + providerRef;
    }

    /** Settlement confirmed only when the enquiry reports {@code status.code == "TS"}. */
    @Override
    protected boolean parseStatus(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(responseBody.strip());
            JsonNode txn = root.path("data").path(TRANSACTION);
            if (txn.isMissingNode() || txn.isNull()) {
                txn = root.path(TRANSACTION);
            }
            return STATUS_SUCCESS.equalsIgnoreCase(statusCode(txn.isMissingNode() ? root : txn));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return false;
        }
    }

    /** Reads {@code status.code} (Airtel nests the code) or a flat {@code status} string. */
    private static String statusCode(JsonNode holder) {
        String code = firstText(holder.path("status"), "code", "message");
        return code != null ? code : firstText(holder, "status");
    }
}
