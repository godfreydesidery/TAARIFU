package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.SmsGateway;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Production {@link SmsGateway} adapter over a generic <b>HTTPS-submit SMS aggregator</b> (PRD §21 EI-3,
 * §18; ARCHITECTURE.md §7). Selected by {@code taarifu.communications.sms.provider=http}.
 *
 * <p>Responsibility: maps the provider-agnostic {@link SmsMessage} to one aggregator submit request — a
 * JSON {@code POST {to, from, text, reference}} to the configured {@code submit-url} with the API key on
 * the configured auth header — and maps the HTTP outcome back to an {@link SmsSendResult}. A 2xx is
 * "accepted/queued"; the per-message <b>delivery receipt</b> (DLR) arrives asynchronously on a separate
 * webhook (out of scope for this outbound adapter). The aggregator's session/DLR/least-cost quirks are
 * confined to this class so the OTP/notification flows never depend on a concrete vendor (DI1).</p>
 *
 * <p><b>Degradation (EI-3, "degrade, don't crash")</b>: this adapter <b>never throws</b> for a routine
 * failure. A non-2xx, a timeout, or a transport error is caught and returned as
 * {@link SmsSendResult#failed(String)} with a <b>non-PII</b> reason, so the caller decides on the
 * fallback (the {@code NotificationDispatchService} records {@code FAILED} / the OTP flow can fall back to
 * email — OTP is never solely SMS-dependent, R29). A short per-request timeout
 * ({@code sms.request-timeout}, default 5s) ensures a slow aggregator never piles up threads.</p>
 *
 * <p><b>Privacy (PRD §18, S-4)</b>: the destination MSISDN and the body (which for OTP carries the code)
 * are sent to the aggregator but <b>never logged</b> — only a masked recipient, the purpose tag, and the
 * body length are logged. The API key is read from {@code sms.api-key} (env, never source) and is never
 * logged.</p>
 *
 * <p><b>WHY a thin {@link RestClient} (no vendor SDK)</b>: the aggregator contract is a single HTTPS POST;
 * a heavyweight SDK would add a dependency and bury the contract. {@code RestClient} ships with
 * {@code spring-boot-starter-web} (already present), so no new dependency. The adapter is unit-testable by
 * constructing it with a {@code RestClient} backed by a mock request factory — the request URL, headers,
 * and body are asserted with <b>no real network</b>.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.communications.sms.provider", havingValue = "http")
public class HttpSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpSmsGateway.class);

    private final CommunicationsChannelProperties.Sms config;
    private final RestClient restClient;

    /**
     * Production constructor: builds a {@link RestClient} with the configured per-request timeout.
     *
     * @param properties the bound channel settings; the SMS group must carry a non-blank {@code submit-url}
     *                   and {@code sender-id} when this adapter is active.
     * @throws IllegalStateException if the submit URL or sender-id is absent — booting an active HTTP SMS
     *                               adapter without them is a misconfiguration that must fail fast rather
     *                               than 500 on the first OTP.
     */
    @Autowired
    public HttpSmsGateway(CommunicationsChannelProperties properties) {
        this(properties, defaultClient(properties.sms()));
    }

    /**
     * Full constructor (also the unit-test seam): takes a ready {@link RestClient} so a test can bind a
     * mock transport (e.g. {@code MockRestServiceServer}) and assert the request with no network.
     *
     * @param properties the bound channel settings.
     * @param restClient the HTTP client (timeout-configured in prod; mock-transport in tests).
     * @throws IllegalStateException if the submit URL or sender-id is blank (fail-fast misconfiguration).
     */
    HttpSmsGateway(CommunicationsChannelProperties properties, RestClient restClient) {
        this.config = properties.sms();
        if (config.submitUrl() == null) {
            throw new IllegalStateException(
                    "taarifu.communications.sms.submit-url must be set when sms.provider=http. "
                    + "Provide the aggregator HTTPS submit endpoint via the environment.");
        }
        if (config.senderId() == null) {
            throw new IllegalStateException(
                    "taarifu.communications.sms.sender-id must be set when sms.provider=http "
                    + "(the TCRA-registered sender-id / shortcode, D-Q7).");
        }
        this.restClient = restClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Submits {@code {to, from, text, reference}} as JSON. The {@code reference} carries the caller's
     * idempotency key so the aggregator (and our DLR correlation) can dedup a relay retry. Returns
     * {@code accepted} on 2xx, {@code failed(reason)} on any other outcome — never throwing.</p>
     */
    @Override
    public SmsSendResult send(SmsMessage message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("to", message.recipientE164());
        body.put("from", config.senderId());
        body.put("text", message.body());
        // Non-PII correlation/idempotency reference so a retry is deduped, not double-sent (DI4).
        body.put("reference", message.idempotencyKey());
        try {
            String providerId = restClient.post()
                    .uri(config.submitUrl())
                    .header(config.authHeader(), bearerValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            // Redacted: never log the MSISDN or body (the OTP lives there — S-4, PRD §18).
            log.info("SMS submitted: to={}, purpose={}, len={}, accepted",
                    mask(message.recipientE164()), message.purpose(),
                    message.body() == null ? 0 : message.body().length());
            return SmsSendResult.accepted(extractMessageId(providerId, message.idempotencyKey()));
        } catch (RuntimeException ex) {
            // Degrade, don't crash (EI-3): the caller falls back. Reason is the exception type only — never
            // the response body (which could echo the MSISDN/text) and never a stack trace (PRD §18).
            log.warn("SMS submit failed: to={}, purpose={}, reason={}",
                    mask(message.recipientE164()), message.purpose(), ex.getClass().getSimpleName());
            return SmsSendResult.failed("SMS_SUBMIT_FAILED");
        }
    }

    /**
     * Builds the auth-header value. For the default {@code Authorization} header the key is sent as a
     * {@code Bearer} token; for a custom header (e.g. {@code X-API-Key}) the raw key is sent. Returns an
     * empty string if no key is configured (the aggregator will reject — surfaced as a failed send).
     */
    private String bearerValue() {
        if (config.apiKey() == null) {
            return "";
        }
        return "Authorization".equalsIgnoreCase(config.authHeader())
                ? "Bearer " + config.apiKey()
                : config.apiKey();
    }

    /** Falls back to the idempotency key as the message id when the aggregator returns no/blank body. */
    private static String extractMessageId(String responseBody, String fallback) {
        return (responseBody == null || responseBody.isBlank()) ? fallback : responseBody.strip();
    }

    /** Builds the prod {@link RestClient} with the configured connect/read timeout. */
    private static RestClient defaultClient(CommunicationsChannelProperties.Sms sms) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) sms.requestTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Masks a phone to {@code +2557…masked} so logs carry no full MSISDN (S-4, PDPA). */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) {
            return "***";
        }
        return phone.substring(0, 5) + "…masked";
    }
}
