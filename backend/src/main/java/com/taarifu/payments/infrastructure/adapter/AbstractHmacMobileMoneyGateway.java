package com.taarifu.payments.infrastructure.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared base for the real Tanzanian mobile-money adapters (M-Pesa / Tigo Pesa / Airtel Money / HaloPesa)
 * (ADR-0015; PRD §23.5/§23.6, §21 EI-20, §18).
 *
 * <p>Responsibility: the rails share the <i>transport &amp; security</i> shape — an HTTPS collection submit,
 * an <b>HMAC-SHA256 callback verification over the raw body</b> (constant-time, fail-closed), an out-of-band
 * settlement check, fail-fast config validation, and MSISDN masking — so that machinery lives here once
 * (DRY). What genuinely <b>differs per rail</b> — the collection request body, the collection/status URL
 * paths, the callback field naming, and the provider→settled status mapping — is delegated to small
 * {@code protected} <b>hooks</b> each subclass overrides. This is the Template-Method seam that lets every
 * adapter be a <i>real</i> per-rail binding (Daraja-style M-Pesa, Mixx Tigo Pesa, Airtel Money, HaloPesa)
 * without re-implementing HMAC/transport four times. Vendor specifics stay confined to
 * {@code infrastructure.adapter}, never the domain (DI1).</p>
 *
 * <p><b>🔒 Secrets from env only (PRD §18, CLAUDE.md §12):</b> the HMAC secret and base URL come from
 * {@link PaymentsGatewayProperties} (bound from {@code taarifu.payments.gateway.*}); none is in source. An
 * active real adapter with a blank secret or base URL fails fast at construction rather than silently
 * accepting forged callbacks or 500-ing on the first top-up.</p>
 *
 * <p><b>Never-trust-the-callback (PRD §23.5):</b> {@link #verifyCallbackSignature} is the fail-closed first
 * gate (constant-time compare, no oracle); {@link #verifySettled} is the authoritative confirmation against
 * the provider. The callback's claimed outcome alone never credits a wallet.</p>
 *
 * <p><b>Degrade-don't-crash (EI-20):</b> a transport failure on initiation returns a not-accepted result,
 * never an exception that 500s the citizen; the caller surfaces a typed {@code SERVICE_UNAVAILABLE} and the
 * free path continues.</p>
 *
 * <p><b>Privacy (PRD §18):</b> the MSISDN/body are sent to the rail but never logged; only a masked
 * recipient and a presence flag are logged. The HMAC secret is never logged.</p>
 */
abstract class AbstractHmacMobileMoneyGateway implements MobileMoneyGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractHmacMobileMoneyGateway.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Parses a (signature-verified) callback / status body. A shared instance is fine — {@link ObjectMapper}
     * is thread-safe for reads; the callback parse never trusts the body for crediting (only reconciliation,
     * after {@link #verifySettled} confirms against the rail, ever credits).
     */
    protected static final ObjectMapper JSON = new ObjectMapper();

    /** Bound gateway settings (base URL, HMAC secret, timeout, optional merchant id). */
    protected final PaymentsGatewayProperties config;
    private final RestClient restClient;

    /**
     * @param config bound gateway settings; an active real adapter requires a non-blank base URL and HMAC
     *               secret — missing either is a misconfiguration that must fail fast (rather than 500 on
     *               the first top-up or, worse, accept a forged callback).
     * @throws IllegalStateException if the base URL or HMAC secret is blank.
     */
    protected AbstractHmacMobileMoneyGateway(PaymentsGatewayProperties config) {
        this(config, defaultClient(config));
    }

    /**
     * Full constructor (also the unit-test seam): takes a ready {@link RestClient} so a test can bind a mock
     * transport and assert the request with no network.
     *
     * @param config     bound gateway settings.
     * @param restClient the HTTP client (timeout-configured in prod; mock-transport in tests).
     * @throws IllegalStateException if the base URL or HMAC secret is blank.
     */
    protected AbstractHmacMobileMoneyGateway(PaymentsGatewayProperties config, RestClient restClient) {
        this.config = config;
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "taarifu.payments.gateway.base-url must be set when a real mobile-money rail is active. "
                    + "Provide the rail HTTPS base URL via the environment.");
        }
        if (config.hmacSecret() == null || config.hmacSecret().isBlank()) {
            throw new IllegalStateException(
                    "taarifu.payments.gateway.hmac-secret must be set when a real mobile-money rail is "
                    + "active (callback HMAC verification fails closed without it). Provide it via the "
                    + "environment / secret manager — never in source (PRD §18, CLAUDE.md §12).");
        }
        this.restClient = restClient;
    }

    // ------------------------------------------------------------------------------------------------
    // Per-rail hooks (Template Method). Subclasses override these to encode their REAL vendor shape.
    // Defaults are the generic /collections contract used by the sandbox harness so a rail that genuinely
    // matches it need override nothing.
    // ------------------------------------------------------------------------------------------------

    /**
     * The relative path (appended to {@link PaymentsGatewayProperties#baseUrl()}) the rail accepts a
     * collection (STK-push) submit on. Override per rail (e.g. Daraja
     * {@code /mpesa/stkpush/v1/processrequest}).
     *
     * @return the collection submit path; default {@code /collections}.
     */
    protected String collectionPath() {
        return "/collections";
    }

    /**
     * Builds the rail-specific JSON request body for a collection (STK push). Override per rail to emit that
     * rail's exact field names (M-Pesa {@code BusinessShortCode/PhoneNumber/AccountReference}, Airtel's nested
     * {@code subscriber/transaction}, etc.). The default is the generic flat sandbox shape.
     *
     * <p>WHY a body the subclass owns: the request shape is the single biggest real per-rail difference; the
     * MSISDN/amount/reference are passed in already validated so the subclass only maps names — no PII handling
     * leaks beyond this method, and the body is never logged.</p>
     *
     * @param request the initiation request (amount in minor units, payer MSISDN, our idempotency key).
     * @return a JSON-serialisable map (insertion order preserved for deterministic tests).
     */
    protected Map<String, Object> buildCollectionBody(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amountMinor());
        body.put("currency", request.currency());
        body.put("msisdn", request.payerMsisdn());
        body.put("reference", request.idempotencyKey());
        return body;
    }

    /**
     * Extracts the provider reference from the (string) collection-submit response. Override per rail if the
     * rail returns a JSON envelope rather than a bare reference. The default falls back to the idempotency key
     * when the rail returns no/blank body (the sandbox harness echoes it).
     *
     * @param responseBody the rail's submit response body (may be {@code null}/blank).
     * @param fallbackRef  our idempotency key, used when the rail returns nothing usable.
     * @return the provider correlation reference reconciliation will key on.
     */
    protected String extractProviderRef(String responseBody, String fallbackRef) {
        return (responseBody == null || responseBody.isBlank()) ? fallbackRef : responseBody.strip();
    }

    /**
     * Parses a (already signature-verified) callback JSON tree into the {@code (providerRef, settled)} fields
     * reconciliation needs, using <b>this rail's</b> field naming and success convention. Override per rail.
     * The default reads the generic aliases ({@code reference}/{@code ref}, {@code resultCode==0} or a
     * {@code SUCCESS}/{@code COMPLETED} status).
     *
     * <p>The parsed {@code settled} is the callback's <i>claim</i> only — it never credits; only
     * {@link #verifySettled} (against the rail) authorises a credit (never-trust-the-callback, PRD §23.5).</p>
     *
     * @param root the parsed callback JSON.
     * @return the parsed reference + claimed-settled flag.
     */
    protected CallbackResult parseCallbackBody(JsonNode root) {
        String providerRef = firstText(root, "providerRef", "reference", "ref", "transactionId");
        boolean settled = genericClaimedSettled(root);
        return new CallbackResult(providerRef, settled);
    }

    /**
     * The relative path the rail confirms a reference's settlement status on (out-of-band verify). Override
     * per rail (e.g. M-Pesa {@code /mpesa/stkpushquery/v1/query}). The default is the generic
     * {@code /collections/{ref}/status} the sandbox harness exposes.
     *
     * @param providerRef the settlement reference.
     * @return the status path (already containing the reference).
     */
    protected String statusPath(String providerRef) {
        return "/collections/" + providerRef + "/status";
    }

    /**
     * Interprets the rail's status-query response body as a definitive settled / not-settled signal. Override
     * per rail to read that rail's success convention. The default accepts a bare JSON boolean {@code true} or
     * the same success aliases as a callback.
     *
     * @param responseBody the status-query response body (may be {@code null}/blank).
     * @return {@code true} iff the rail confirms settlement.
     */
    protected boolean parseStatus(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        String trimmed = responseBody.strip();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        try {
            return genericClaimedSettled(JSON.readTree(trimmed));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Port implementation (transport + security — shared).
    // ------------------------------------------------------------------------------------------------

    /**
     * Submits an STK-push collection using the rail's body shape and path. Degrades to a not-accepted result
     * on any transport failure (EI-20).
     *
     * @param request the initiation request.
     * @return the provider reference + accepted flag; {@code (null, false)} on failure.
     */
    @Override
    public InitiationResult initiateCollection(CollectionRequest request) {
        try {
            String responseBody = restClient.post()
                    .uri(config.baseUrl() + collectionPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildCollectionBody(request))
                    .retrieve()
                    .body(String.class);
            log.info("Top-up collection submitted: provider={}, to={}, refPresent={}, accepted",
                    provider(), mask(request.payerMsisdn()),
                    responseBody != null && !responseBody.isBlank());
            return new InitiationResult(extractProviderRef(responseBody, request.idempotencyKey()), true);
        } catch (RuntimeException ex) {
            // Degrade, don't crash (EI-20): the caller surfaces SERVICE_UNAVAILABLE; the free path continues.
            log.warn("Top-up collection failed: provider={}, to={}, reason={}",
                    provider(), mask(request.payerMsisdn()), ex.getClass().getSimpleName());
            return new InitiationResult(null, false);
        }
    }

    /**
     * Verifies an HMAC-SHA256 hex signature over the raw body using the per-rail secret — fail-closed,
     * constant-time.
     *
     * @param rawBody         the exact callback body bytes.
     * @param signatureHeader the hex-encoded signature the rail presented.
     * @return {@code true} iff the signature matches; {@code false} on any mismatch/error (no oracle).
     */
    @Override
    public boolean verifyCallbackSignature(byte[] rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(config.hmacSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] expected = mac.doFinal(rawBody);
            byte[] presented = HexFormat.of().parseHex(signatureHeader.trim().toLowerCase());
            // Constant-time compare so verification time leaks nothing about the secret (timing-safe).
            return MessageDigest.isEqual(expected, presented);
        } catch (RuntimeException | java.security.GeneralSecurityException ex) {
            // Any parse/crypto error → fail closed; never log the secret or the body.
            log.warn("Callback signature verification error: provider={}, reason={}",
                    provider(), ex.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Parses a (already signature-verified) callback body into the {@code (providerRef, settled)} fields
     * reconciliation needs, delegating the rail-specific field reading to {@link #parseCallbackBody(JsonNode)}.
     *
     * <p>A malformed body yields a not-settled, no-reference result so a garbage callback changes nothing.</p>
     *
     * @param rawBody the callback body bytes (verified by {@link #verifyCallbackSignature} first).
     * @return the parsed reference + claimed-settled flag; {@code (null, false)} on any parse failure.
     */
    @Override
    public CallbackResult parseCallback(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return new CallbackResult(null, false);
        }
        try {
            JsonNode root = JSON.readTree(rawBody);
            return parseCallbackBody(root);
        } catch (java.io.IOException | RuntimeException ex) {
            // Never log the body (it may carry an MSISDN/PII). A malformed callback is a benign no-op.
            log.warn("Callback parse failed: provider={}, reason={}", provider(), ex.getClass().getSimpleName());
            return new CallbackResult(null, false);
        }
    }

    /**
     * Confirms settlement against the rail's status endpoint (the authoritative signal), interpreting the
     * response via {@link #parseStatus(String)}. Degrades to not-settled on any transport failure — a top-up is
     * never credited on an unconfirmed reference.
     *
     * @param providerRef the settlement reference.
     * @return {@code true} iff the rail confirms settlement.
     */
    @Override
    public boolean verifySettled(String providerRef) {
        if (providerRef == null || providerRef.isBlank()) {
            return false;
        }
        try {
            String responseBody = restClient.get()
                    .uri(config.baseUrl() + statusPath(providerRef))
                    .retrieve()
                    .body(String.class);
            return parseStatus(responseBody);
        } catch (RuntimeException ex) {
            log.warn("Settlement verification failed: provider={}, reason={}",
                    provider(), ex.getClass().getSimpleName());
            return false; // never-trust + degrade: unconfirmed → not credited.
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Shared helpers reusable by subclass hooks.
    // ------------------------------------------------------------------------------------------------

    /**
     * Returns the first non-blank text value among the candidate field names at the given node, or
     * {@code null}. Reusable by subclass {@link #parseCallbackBody} implementations.
     *
     * @param root   the JSON node to read.
     * @param fields candidate field names, in priority order.
     * @return the first present, non-blank, stripped value, or {@code null}.
     */
    protected static String firstText(JsonNode root, String... fields) {
        if (root == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode node = root.get(field);
            if (node != null && node.isValueNode()) {
                String text = node.asText(null);
                if (text != null && !text.isBlank()) {
                    return text.strip();
                }
            }
        }
        return null;
    }

    /**
     * Reads a generic <i>claimed</i> settlement outcome from common alias fields: a boolean {@code settled},
     * an M-Pesa-style numeric {@code resultCode == 0}, or a {@code SUCCESS}/{@code COMPLETED}/{@code SETTLED}
     * status. Defaults to {@code false} (claim nothing) — the safe default, since the claim never credits on
     * its own. Reusable by subclass hooks that want the common convention.
     *
     * @param root the JSON node to read.
     * @return the claimed-settled flag.
     */
    protected static boolean genericClaimedSettled(JsonNode root) {
        JsonNode settledNode = root.get("settled");
        if (settledNode != null && settledNode.isBoolean()) {
            return settledNode.booleanValue();
        }
        JsonNode resultCode = root.get("resultCode");
        if (resultCode != null && resultCode.isNumber()) {
            return resultCode.asInt() == 0; // M-Pesa convention: resultCode 0 == success.
        }
        String status = firstText(root, "status", "state");
        return status != null && ("SUCCESS".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)
                || "SETTLED".equalsIgnoreCase(status));
    }

    /** Builds the prod {@link RestClient} with the configured connect/read timeout. */
    private static RestClient defaultClient(PaymentsGatewayProperties config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) config.requestTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Masks a phone to {@code +2557…masked} so logs carry no full MSISDN (PRD §18, PDPA). */
    protected static String mask(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 5) + "…masked";
    }
}
