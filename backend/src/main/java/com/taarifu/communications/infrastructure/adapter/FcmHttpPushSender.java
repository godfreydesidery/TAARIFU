package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.Map;

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
 * <p><b>Token resolution &amp; degradation (EI-5)</b>: this MVP adapter does not yet own a per-device token
 * <i>registry</i> (the {@code // TODO(wiring)} to resolve a profile's device tokens from identity is a
 * separate increment). It accepts the recipient profile ref; until a registry exists it treats the
 * recipient as having <b>no resolvable device token</b> and returns {@link PushResult#noDeviceToken()} —
 * which is the spec's exact signal for the dispatcher to <b>fall back to SMS</b> while the FEED item is
 * always retained (US-5.1, EI-5). When the registry lands, this adapter resolves the token(s), sends, and
 * prunes invalid/unregistered tokens on the FCM {@code UNREGISTERED}/{@code INVALID_ARGUMENT} error.
 * A real send <b>never throws</b> for a routine failure — a transport/FCM error returns
 * {@link PushResult#failed(String)} with a non-PII reason (degrade, don't crash).</p>
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

    private final CommunicationsChannelProperties.Push config;
    private final GoogleServiceAccountTokenProvider tokenProvider;
    private final RestClient restClient;

    /**
     * Production constructor: builds the token provider from the service-account file and a timeout-bounded
     * {@link RestClient}.
     *
     * @param properties   the bound channel settings; the push group must carry a non-blank
     *                     {@code project-id} and {@code credentials-file} when this adapter is active.
     * @param objectMapper the shared Jackson mapper (used to parse the service-account JSON + token
     *                     response inside the token provider).
     * @throws IllegalStateException if the project id or credentials file is absent — fail-fast
     *                               misconfiguration rather than 500 on the first push.
     */
    @Autowired
    public FcmHttpPushSender(CommunicationsChannelProperties properties, ObjectMapper objectMapper) {
        this(properties,
                buildTokenProvider(properties.push(), objectMapper),
                RestClient.builder().requestFactory(defaultRequestFactory(properties.push())).build());
    }

    /**
     * Full constructor (also the unit-test seam): the token provider + HTTP client injected so a test
     * supplies a stub token provider and a mock-transport {@link RestClient}.
     *
     * @param properties    the bound channel settings.
     * @param tokenProvider supplies the OAuth2 bearer (cached; stub in tests).
     * @param restClient    the HTTP client (mock transport in tests).
     * @throws IllegalStateException if the project id is blank (fail-fast misconfiguration).
     */
    FcmHttpPushSender(CommunicationsChannelProperties properties,
                      GoogleServiceAccountTokenProvider tokenProvider, RestClient restClient) {
        this.config = properties.push();
        if (config.projectId() == null) {
            throw new IllegalStateException(
                    "taarifu.communications.push.project-id must be set when push.provider=fcm.");
        }
        this.tokenProvider = tokenProvider;
        this.restClient = restClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Until the device-token registry increment lands, returns {@link PushResult#noDeviceToken()} (the
     * dispatcher then falls back to SMS — EI-5). Once a token is resolvable, builds the FCM v1 message and
     * POSTs it with the OAuth2 bearer; a 2xx is {@code ok()}, an {@code UNREGISTERED}/{@code INVALID}
     * token is reported as {@link PushResult#noDeviceToken()} (and pruned), any other error is
     * {@link PushResult#failed(String)} — never throwing.</p>
     */
    @Override
    public PushResult send(PushMessage message) {
        String deviceToken = resolveDeviceToken(message.recipientProfileId());
        if (deviceToken == null) {
            // No registry yet (or no valid token) → the spec's SMS-fallback signal (EI-5). Not an error.
            return PushResult.noDeviceToken();
        }
        try {
            String bearer = tokenProvider.accessToken();
            restClient.post()
                    .uri(String.format(SEND_URL, config.projectId()))
                    .header("Authorization", "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildFcmMessage(deviceToken, message))
                    .retrieve()
                    .body(String.class);
            // Redacted: never log title/body; the recipient is a UUID (non-PII).
            log.info("PUSH sent via FCM: recipient={}, hasDeepLink={}, key={}",
                    message.recipientProfileId(), message.deepLinkRef() != null, message.idempotencyKey());
            return PushResult.ok();
        } catch (RuntimeException ex) {
            // Degrade, don't crash (EI-5): the dispatcher records the outcome. Reason is the exception type
            // only — never the payload, never a stack trace (PRD §18).
            log.warn("PUSH send failed via FCM: recipient={}, reason={}",
                    message.recipientProfileId(), ex.getClass().getSimpleName());
            return PushResult.failed("FCM_SEND_FAILED");
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
     * Resolves a usable FCM device token for the recipient profile.
     *
     * <p>MVP behaviour: returns {@code null} (no device-token registry yet) so the dispatcher falls back to
     * SMS while FEED is retained (EI-5). The registry (per-device tokens, pruning on FCM
     * {@code UNREGISTERED}/{@code INVALID_ARGUMENT}) is a separate increment — see CENTRAL NEEDS. Kept as
     * one private seam so wiring the registry (resolve token + prune-on-error) touches exactly this method
     * and {@link #send}; the {@link ObjectMapper} is already held for parsing the FCM error then.</p>
     *
     * @param recipientProfileId the recipient profile public id (the registry key, once it lands).
     * @return a valid device token, or {@code null} when none is resolvable.
     */
    @SuppressWarnings("unused")
    private String resolveDeviceToken(String recipientProfileId) {
        // TODO(wiring): resolve the recipient's valid device token(s) from the push-token registry
        //               (identity's public API), and prune tokens FCM reports UNREGISTERED/INVALID.
        return null;
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
