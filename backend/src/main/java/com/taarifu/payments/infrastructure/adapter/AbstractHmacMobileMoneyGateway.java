package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
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
 * <p>Responsibility: the rails share one shape — an HTTPS collection submit, an <b>HMAC-SHA256 callback
 * verification over the raw body</b>, and an out-of-band settlement check — so that shape lives here once
 * (DRY); per-rail subclasses only declare their {@link MobileMoneyProvider}. Vendor specifics (exact field
 * names, the verify path) are confined to {@code infrastructure.adapter}, never the domain (DI1).</p>
 *
 * <p><b>🔒 Secrets from env only (PRD §18, CLAUDE.md §12):</b> the HMAC secret and base URL come from
 * {@link PaymentsGatewayProperties} (bound from {@code taarifu.payments.gateway.*}); none is in source. An
 * active real adapter with a blank secret fails fast at construction rather than silently accepting forged
 * callbacks.</p>
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

    /** Bound gateway settings (base URL, HMAC secret, timeout). */
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

    /**
     * Submits an STK-push collection. Degrades to a not-accepted result on any transport failure.
     *
     * @param request the initiation request.
     * @return the provider reference + accepted flag; {@code (null, false)} on failure.
     */
    @Override
    public InitiationResult initiateCollection(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", request.amountMinor());
        body.put("currency", request.currency());
        body.put("msisdn", request.payerMsisdn());
        body.put("reference", request.idempotencyKey());
        try {
            String providerRef = restClient.post()
                    .uri(config.baseUrl() + "/collections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("Top-up collection submitted: provider={}, to={}, refPresent={}, accepted",
                    provider(), mask(request.payerMsisdn()),
                    providerRef != null && !providerRef.isBlank());
            return new InitiationResult(extractRef(providerRef, request.idempotencyKey()), true);
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
     * Confirms settlement against the rail's verify endpoint (the authoritative signal). Degrades to
     * not-settled on any transport failure — a top-up is never credited on an unconfirmed reference.
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
            Boolean settled = restClient.get()
                    .uri(config.baseUrl() + "/collections/{ref}/status", providerRef)
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(settled);
        } catch (RuntimeException ex) {
            log.warn("Settlement verification failed: provider={}, reason={}",
                    provider(), ex.getClass().getSimpleName());
            return false; // never-trust + degrade: unconfirmed → not credited.
        }
    }

    /** Builds the prod {@link RestClient} with the configured connect/read timeout. */
    private static RestClient defaultClient(PaymentsGatewayProperties config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) config.requestTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Falls back to the idempotency key as the reference when the rail returns no/blank body. */
    private static String extractRef(String responseBody, String fallback) {
        return (responseBody == null || responseBody.isBlank()) ? fallback : responseBody.strip();
    }

    /** Masks a phone to {@code +2557…masked} so logs carry no full MSISDN (PRD §18, PDPA). */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 5) + "…masked";
    }
}
