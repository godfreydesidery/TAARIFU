package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.communications.domain.port.DeviceTokenRegistry;
import com.taarifu.communications.domain.port.PushSender;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Production {@link PushSender} adapter over <b>FCM HTTP v1</b> (PRD §21 EI-5, §18; ARCHITECTURE.md §7).
 * Selected by {@code taarifu.communications.push.provider=fcm}.
 *
 * <p>Responsibility: maps the provider-agnostic {@link PushMessage} to one FCM v1 {@code messages:send}
 * request — a minimal {@code notification{title,body}} + a {@code data} deep-link ref — and POSTs it to
 * {@code https://fcm.googleapis.com/v1/projects/<projectId>/messages:send} with a short-lived OAuth2
 * bearer. The bearer is obtained by {@link GoogleServiceAccountTokenProvider} (service-account JWT →
 * token exchange), so this is a <b>thin HTTP adapter — no firebase-admin SDK</b> (DI1, the task's
 * directive). FCM's quirks are confined to this class so the dispatcher never depends on the vendor.</p>
 *
 * <p><b>Token resolution, fan-out &amp; degradation (EI-5)</b>: this adapter resolves the recipient's live
 * device tokens from the {@link DeviceTokenRegistry} (the {@code communications} device-token registry) and
 * <b>fans the push out to every registered device</b> — a citizen with a phone and a tablet hears on both.
 * If the recipient has <b>no registered token</b>, it returns {@link PushResult#noDeviceToken()} — the
 * spec's exact signal for the dispatcher to <b>fall back to SMS</b> while the FEED item is always retained
 * (US-5.1, EI-5). For each device it POSTs the FCM v1 message; on an FCM {@code UNREGISTERED}/
 * {@code INVALID_ARGUMENT} response (the token is dead) it <b>prunes that token from the registry</b> and
 * treats the device as unreachable. The aggregate result is {@code ok()} if <i>at least one</i> device
 * accepted; {@code noDeviceToken()} if every token was dead/pruned (so the dispatcher still falls back to
 * SMS); {@link PushResult#failed(String)} only on a transient transport/FCM error with no acceptance. A
 * send <b>never throws</b> for a routine failure (degrade, don't crash).</p>
 *
 * <p><b>Privacy (PRD §18)</b>: push payloads transit third-party infrastructure, so the body stays minimal
 * (title + short body + opaque deep-link ref) and is <b>never logged</b>; the recipient is a UUID. The
 * service-account private key is loaded from a file/secret path ({@code push.credentials-file}, env) and
 * is never logged or committed.</p>
 *
 * <p><b>WHY a thin {@link RestClient} + Nimbus-signed JWT (no SDK)</b>: FCM v1 is two HTTPS calls (token
 * exchange + send); firebase-admin is a heavy transitive dependency we do not need. {@code RestClient}
 * (already on the classpath via web) and Nimbus JOSE (already on the classpath for our own JWTs) cover it.
 * The adapter is unit-testable by injecting a token provider returning a fixed bearer and a
 * {@code RestClient} over a mock request factory — the send URL, auth header, and FCM message body are
 * asserted with <b>no real network and no real Google credentials</b>.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.communications.push.provider", havingValue = "fcm")
public class FcmHttpPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmHttpPushSender.class);

    /** FCM HTTP v1 send endpoint template; the project id is substituted in. */
    private static final String SEND_URL = "https://fcm.googleapis.com/v1/projects/%s/messages:send";

    /**
     * The FCM v1 error status that means "this token is permanently dead — stop sending to it and prune it"
     * ({@code UNREGISTERED} = app uninstalled/token expired; {@code INVALID_ARGUMENT} = malformed token).
     * Any other status is treated as transient (retry/degrade), so a healthy token is never wrongly pruned.
     */
    private static final java.util.Set<String> FCM_DEAD_TOKEN_STATUSES =
            java.util.Set.of("UNREGISTERED", "INVALID_ARGUMENT");

    private final CommunicationsChannelProperties.Push config;
    private final GoogleServiceAccountTokenProvider tokenProvider;
    private final RestClient restClient;
    private final DeviceTokenRegistry deviceTokenRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Production constructor: builds the token provider from the service-account file and a timeout-bounded
     * {@link RestClient}.
     *
     * @param properties          the bound channel settings; the push group must carry a non-blank
     *                            {@code project-id} and {@code credentials-file} when this adapter is active.
     * @param objectMapper        the shared Jackson mapper (parses the service-account JSON + token response
     *                            in the token provider, and the FCM error body to detect dead tokens here).
     * @param deviceTokenRegistry the registry resolving a recipient's live device tokens and pruning dead
     *                            ones (the push fan-out target; ports-and-adapters, ADR-0004).
     * @throws IllegalStateException if the project id or credentials file is absent — fail-fast
     *                               misconfiguration rather than 500 on the first push.
     */
    @Autowired
    public FcmHttpPushSender(CommunicationsChannelProperties properties, ObjectMapper objectMapper,
                             DeviceTokenRegistry deviceTokenRegistry) {
        this(properties,
                buildTokenProvider(properties.push(), objectMapper),
                RestClient.builder().requestFactory(defaultRequestFactory(properties.push())).build(),
                deviceTokenRegistry, objectMapper);
    }

    /**
     * Full constructor (also the unit-test seam): the token provider, HTTP client, registry, and mapper
     * injected so a test supplies a stub token provider, a mock-transport {@link RestClient}, and an
     * in-memory registry.
     *
     * @param properties          the bound channel settings.
     * @param tokenProvider       supplies the OAuth2 bearer (cached; stub in tests).
     * @param restClient          the HTTP client (mock transport in tests).
     * @param deviceTokenRegistry the device-token registry (fake in tests).
     * @param objectMapper        the Jackson mapper used to parse FCM error bodies.
     * @throws IllegalStateException if the project id is blank (fail-fast misconfiguration).
     */
    FcmHttpPushSender(CommunicationsChannelProperties properties,
                      GoogleServiceAccountTokenProvider tokenProvider, RestClient restClient,
                      DeviceTokenRegistry deviceTokenRegistry, ObjectMapper objectMapper) {
        this.config = properties.push();
        if (config.projectId() == null) {
            throw new IllegalStateException(
                    "taarifu.communications.push.project-id must be set when push.provider=fcm.");
        }
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
        this.deviceTokenRegistry = deviceTokenRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolves the recipient's live device tokens from the registry and fans the push out to each. With
     * no registered token, returns {@link PushResult#noDeviceToken()} (the dispatcher then falls back to
     * SMS — EI-5). Per device: builds the FCM v1 message and POSTs it with the OAuth2 bearer; a 2xx counts
     * as an acceptance; an {@code UNREGISTERED}/{@code INVALID_ARGUMENT} token is pruned from the registry
     * and counts as unreachable; any other error is transient (no prune). The aggregate is {@code ok()} if
     * any device accepted, {@code noDeviceToken()} if every token was dead/pruned, else
     * {@link PushResult#failed(String)} — never throwing.</p>
     */
    @Override
    public PushResult send(PushMessage message) {
        List<String> tokens = resolveDeviceTokens(message.recipientProfileId());
        if (tokens.isEmpty()) {
            // No registered device → the spec's SMS-fallback signal (EI-5). Not an error.
            return PushResult.noDeviceToken();
        }
        String bearer = tokenProvider.accessToken();
        boolean anyAccepted = false;
        boolean anyTransientFailure = false;
        int prunedCount = 0;
        for (String deviceToken : tokens) {
            SendOutcome outcome = sendToDevice(bearer, deviceToken, message);
            switch (outcome) {
                case ACCEPTED -> anyAccepted = true;
                case DEAD_TOKEN -> {
                    // The token is permanently dead — prune it so we never push to it again (EI-5).
                    deviceTokenRegistry.pruneInvalid(deviceToken);
                    prunedCount++;
                }
                case TRANSIENT_FAILURE -> anyTransientFailure = true;
            }
        }
        // Redacted: log the recipient UUID + counts only; never the token string or title/body (PRD §18).
        log.info("PUSH fan-out via FCM: recipient={}, devices={}, accepted={}, pruned={}, key={}",
                message.recipientProfileId(), tokens.size(), anyAccepted, prunedCount,
                message.idempotencyKey());

        if (anyAccepted) {
            return PushResult.ok();
        }
        if (anyTransientFailure) {
            // No device accepted and at least one failed transiently → a real failure (the dispatcher
            // records it); not the no-token fall-back, since the tokens may still be valid.
            return PushResult.failed("FCM_SEND_FAILED");
        }
        // Every token was dead and pruned → the recipient now has no reachable device: fall back to SMS.
        return PushResult.noDeviceToken();
    }

    /** The per-device send classification used to aggregate a multi-device fan-out result. */
    private enum SendOutcome {
        /** FCM accepted the message for this device (2xx). */
        ACCEPTED,
        /** FCM reported the token permanently dead ({@code UNREGISTERED}/{@code INVALID_ARGUMENT}) → prune. */
        DEAD_TOKEN,
        /** A transient transport/FCM error — retry/degrade, do NOT prune a possibly-healthy token. */
        TRANSIENT_FAILURE
    }

    /**
     * POSTs the FCM v1 message to one device and classifies the outcome — never throwing.
     *
     * @param bearer      the OAuth2 bearer for the send.
     * @param deviceToken the resolved FCM registration token (secret; never logged).
     * @param message     the port message (already-localised title/body, opaque deep-link ref).
     * @return the {@link SendOutcome} for this device.
     */
    private SendOutcome sendToDevice(String bearer, String deviceToken, PushMessage message) {
        try {
            restClient.post()
                    .uri(String.format(SEND_URL, config.projectId()))
                    .header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildFcmMessage(deviceToken, message))
                    .retrieve()
                    .body(String.class);
            return SendOutcome.ACCEPTED;
        } catch (RestClientResponseException ex) {
            // An HTTP error carries an FCM error body — inspect its status to decide prune vs. retry.
            boolean dead = isDeadTokenError(ex.getResponseBodyAsString());
            // Redacted: never log the token or payload; the recipient is a UUID (non-PII).
            log.warn("PUSH device send failed via FCM: recipient={}, http={}, deadToken={}",
                    message.recipientProfileId(), ex.getStatusCode().value(), dead);
            return dead ? SendOutcome.DEAD_TOKEN : SendOutcome.TRANSIENT_FAILURE;
        } catch (RuntimeException ex) {
            // Transport-level error (timeout, connection) — transient; degrade, don't crash, don't prune.
            log.warn("PUSH device send failed via FCM: recipient={}, reason={}",
                    message.recipientProfileId(), ex.getClass().getSimpleName());
            return SendOutcome.TRANSIENT_FAILURE;
        }
    }

    /**
     * Decides whether an FCM error response body indicates a permanently dead token (so the caller prunes
     * it) versus a transient error (so it is retried, never pruned).
     *
     * <p>FCM v1 returns {@code {"error":{"status":"UNREGISTERED"|"INVALID_ARGUMENT"|...}}}. Only the dead
     * statuses ({@link #FCM_DEAD_TOKEN_STATUSES}) trigger a prune; a parse failure is treated as transient
     * so a malformed/unknown error never causes us to throw away a possibly-healthy token.</p>
     *
     * @param body the raw FCM error response body (may be {@code null}/blank).
     * @return {@code true} only if the body's {@code error.status} is a known dead-token status.
     */
    boolean isDeadTokenError(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode status =
                    objectMapper.readTree(body).path("error").path("status");
            return status.isTextual() && FCM_DEAD_TOKEN_STATUSES.contains(status.asText());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Unparseable error body → treat as transient (never prune on uncertainty).
            return false;
        }
    }

    /**
     * Builds the FCM HTTP v1 {@code message} object: a minimal localised {@code notification} + a
     * {@code data} deep-link ref, addressed to one device token. Package-visible so a unit test asserts the
     * exact request body shape with no network.
     *
     * @param deviceToken the resolved FCM registration token.
     * @param message     the port message (already-localised title/body, opaque deep-link ref).
     * @return the request body map serialised as {@code {"message": {...}}} per the FCM v1 contract.
     */
    Map<String, Object> buildFcmMessage(String deviceToken, PushMessage message) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", message.title() == null ? "" : message.title());
        notification.put("body", message.body() == null ? "" : message.body());

        Map<String, Object> fcmMessage = new LinkedHashMap<>();
        fcmMessage.put("token", deviceToken);
        fcmMessage.put("notification", notification);
        if (message.deepLinkRef() != null) {
            // data is string→string by FCM contract; carries only the opaque deep-link ref (no PII).
            fcmMessage.put("data", Map.of("deepLinkRef", message.deepLinkRef()));
        }
        return Map.of("message", fcmMessage);
    }

    /**
     * Resolves the recipient profile's live FCM device tokens from the registry — the fan-out target list.
     *
     * <p>The recipient profile ref is a {@code UUID} string carried on the {@link PushMessage}; a malformed
     * value (never expected from the dispatcher) yields an empty list (→ SMS fall-back) rather than a crash.
     * The returned tokens are secrets and are never logged here.</p>
     *
     * @param recipientProfileId the recipient profile public id (the registry key).
     * @return the recipient's live device tokens (possibly empty → the dispatcher falls back to SMS, EI-5).
     */
    private List<String> resolveDeviceTokens(String recipientProfileId) {
        try {
            return deviceTokenRegistry.tokensFor(UUID.fromString(recipientProfileId));
        } catch (IllegalArgumentException ex) {
            // Not a UUID — treat as no reachable device (defensive; the dispatcher passes a profile UUID).
            return List.of();
        }
    }

    /** Builds the token provider from the service-account file (fail-fast on a missing credentials path). */
    private static GoogleServiceAccountTokenProvider buildTokenProvider(
            CommunicationsChannelProperties.Push push, ObjectMapper objectMapper) {
        if (push.credentialsFile() == null) {
            throw new IllegalStateException(
                    "taarifu.communications.push.credentials-file must point to the GCP service-account "
                    + "JSON when push.provider=fcm (provide the path via the environment/secret mount).");
        }
        return new GoogleServiceAccountTokenProvider(
                push.credentialsFile(), objectMapper, push.requestTimeout());
    }

    /** Builds the prod request factory with the configured connect/read timeout. */
    private static ClientHttpRequestFactory defaultRequestFactory(
            CommunicationsChannelProperties.Push push) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) push.requestTimeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}
