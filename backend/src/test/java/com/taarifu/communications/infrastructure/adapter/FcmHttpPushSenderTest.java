package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.communications.domain.port.DeviceTokenRegistry;
import com.taarifu.communications.domain.port.PushSender;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link FcmHttpPushSender} — proves the FCM HTTP v1 message body is built to the v1
 * contract, the registry-backed multi-device fan-out + degradation + invalid-token prune behave per spec,
 * and the dead-token detection is correct, all with <b>no real network and no Google credentials</b>
 * (CLAUDE.md §10).
 *
 * <p>Responsibility: the adapter is built over a {@link MockRestServiceServer}-bound {@link RestClient}
 * (no real FCM), a stub {@link GoogleServiceAccountTokenProvider} (no credential file), and an in-memory
 * {@link DeviceTokenRegistry}. The tests assert the load-bearing contract: the body shape is FCM v1
 * ({@code {message:{token, notification:{title,body}, data:{deepLinkRef}}}}, PII-free); a recipient with
 * <b>no token</b> short-circuits to {@link PushSender.PushResult#noDeviceToken()} (SMS fall-back, EI-5);
 * a recipient with two devices fans out to <b>both</b> and is {@code ok()} if any accepts; an
 * {@code UNREGISTERED} token is <b>pruned</b> and, when it is the only device, the result degrades to
 * {@code noDeviceToken()}; a transient 5xx does <b>not</b> prune; and a blank project id fails fast.</p>
 */
class FcmHttpPushSenderTest {

    private static final String PROJECT = "taarifu-prod";
    private static final String SEND_URL =
            "https://fcm.googleapis.com/v1/projects/" + PROJECT + "/messages:send";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** A minimal in-memory {@link DeviceTokenRegistry}; records prunes for assertions. */
    private static final class FakeRegistry implements DeviceTokenRegistry {
        private final Map<UUID, List<String>> tokens = new HashMap<>();
        private final List<String> pruned = new ArrayList<>();

        void put(UUID profile, String... values) {
            tokens.put(profile, new ArrayList<>(List.of(values)));
        }

        @Override
        public List<String> tokensFor(UUID recipientProfileId) {
            return new ArrayList<>(tokens.getOrDefault(recipientProfileId, List.of()));
        }

        @Override
        public void pruneInvalid(String token) {
            pruned.add(token);
        }
    }

    private CommunicationsChannelProperties props(String projectId) {
        CommunicationsChannelProperties.Push push = new CommunicationsChannelProperties.Push(
                "fcm", projectId, "/secrets/sa.json", Duration.ofSeconds(5));
        return new CommunicationsChannelProperties(null, push, null);
    }

    /** A token provider stub that returns a fixed bearer and never reads a file or hits the network. */
    private GoogleServiceAccountTokenProvider stubTokenProvider() {
        return new GoogleServiceAccountTokenProvider(
                "svc@project.iam.gserviceaccount.com", null, objectMapper, RestClient.builder().build()) {
            @Override
            public synchronized String accessToken() {
                return "fixed-bearer";
            }
        };
    }

    /** Bundles the adapter under test, its bound mock-FCM server, and the fake registry. */
    private record Fixture(FcmHttpPushSender adapter, MockRestServiceServer server, FakeRegistry registry) {
    }

    private Fixture fixture(String projectId, FakeRegistry registry) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FcmHttpPushSender adapter = new FcmHttpPushSender(
                props(projectId), stubTokenProvider(), builder.build(), registry, objectMapper);
        return new Fixture(adapter, server, registry);
    }

    @Test
    void buildFcmMessage_matchesV1Contract_withDeepLinkData() {
        FcmHttpPushSender push = fixture(PROJECT, new FakeRegistry()).adapter();
        PushSender.PushMessage msg = new PushSender.PushMessage(
                "11111111-1111-1111-1111-111111111111", "Taarifu", "Ripoti yako imepokelewa",
                "announcement-42", "idem-1");

        Map<String, Object> body = push.buildFcmMessage("device-token-abc", msg);

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) body.get("message");
        assertThat(message).containsEntry("token", "device-token-abc");
        @SuppressWarnings("unchecked")
        Map<String, Object> notification = (Map<String, Object>) message.get("notification");
        assertThat(notification).containsEntry("title", "Taarifu")
                .containsEntry("body", "Ripoti yako imepokelewa");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) message.get("data");
        assertThat(data).containsEntry("deepLinkRef", "announcement-42");
    }

    @Test
    void buildFcmMessage_withoutDeepLink_omitsDataBlock() {
        FcmHttpPushSender push = fixture(PROJECT, new FakeRegistry()).adapter();
        Map<String, Object> body = push.buildFcmMessage("tok", new PushSender.PushMessage(
                "22222222-2222-2222-2222-222222222222", "Taarifu", "Habari", null, "idem-2"));

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) body.get("message");
        assertThat(message).doesNotContainKey("data");
    }

    @Test
    void send_withNoRegisteredToken_returnsNoDeviceToken_andMakesNoCall() {
        // No tokens registered → the spec's SMS-fallback signal (EI-5), and NOT a single FCM call.
        Fixture f = fixture(PROJECT, new FakeRegistry());
        UUID recipient = UUID.fromString("33333333-3333-3333-3333-333333333333");

        PushSender.PushResult result = f.adapter()
                .send(new PushSender.PushMessage(recipient.toString(), "T", "B", null, "idem-3"));

        assertThat(result.noToken()).isTrue();
        assertThat(result.accepted()).isFalse();
        f.server().verify(); // zero expectations set → zero calls made
    }

    @Test
    void send_fansOutToEveryRegisteredDevice_andIsOkIfAnyAccepts() {
        FakeRegistry registry = new FakeRegistry();
        UUID recipient = UUID.randomUUID();
        registry.put(recipient, "tok-phone", "tok-tablet");
        Fixture f = fixture(PROJECT, registry);
        // Expect exactly two sends — one per device — each accepted.
        f.server().expect(times(2), requestTo(SEND_URL))
                .andExpect(jsonPath("$.message.notification.title").value("Taarifu"))
                .andRespond(withSuccess("{\"name\":\"projects/x/messages/1\"}", MediaType.APPLICATION_JSON));

        PushSender.PushResult result = f.adapter().send(new PushSender.PushMessage(
                recipient.toString(), "Taarifu", "Habari", null, "idem-4"));

        assertThat(result.accepted()).isTrue();
        assertThat(registry.pruned).isEmpty();
        f.server().verify();
    }

    @Test
    void send_prunesUnregisteredToken_andDegradesToNoDeviceTokenWhenItWasTheOnlyDevice() {
        FakeRegistry registry = new FakeRegistry();
        UUID recipient = UUID.randomUUID();
        registry.put(recipient, "dead-token");
        Fixture f = fixture(PROJECT, registry);
        // FCM reports the token UNREGISTERED (404 with the v1 error body).
        f.server().expect(requestTo(SEND_URL)).andRespond(MockRestResponseCreators
                .withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"status\":\"UNREGISTERED\"}}"));

        PushSender.PushResult result = f.adapter().send(new PushSender.PushMessage(
                recipient.toString(), "T", "B", null, "idem-5"));

        // The dead token is pruned, and with no reachable device left the dispatcher falls back to SMS.
        assertThat(registry.pruned).containsExactly("dead-token");
        assertThat(result.noToken()).isTrue();
        assertThat(result.accepted()).isFalse();
        f.server().verify();
    }

    @Test
    void send_onTransient5xx_failsWithoutPruning() {
        FakeRegistry registry = new FakeRegistry();
        UUID recipient = UUID.randomUUID();
        registry.put(recipient, "healthy-token");
        Fixture f = fixture(PROJECT, registry);
        f.server().expect(requestTo(SEND_URL)).andRespond(MockRestResponseCreators
                .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"status\":\"UNAVAILABLE\"}}"));

        PushSender.PushResult result = f.adapter().send(new PushSender.PushMessage(
                recipient.toString(), "T", "B", null, "idem-6"));

        // Transient: do NOT prune a possibly-healthy token; report a real failure (not the no-token signal).
        assertThat(registry.pruned).isEmpty();
        assertThat(result.accepted()).isFalse();
        assertThat(result.noToken()).isFalse();
        f.server().verify();
    }

    @Test
    void isDeadTokenError_recognisesOnlyFcmDeadStatuses() {
        FcmHttpPushSender push = fixture(PROJECT, new FakeRegistry()).adapter();

        assertThat(push.isDeadTokenError("{\"error\":{\"status\":\"UNREGISTERED\"}}")).isTrue();
        assertThat(push.isDeadTokenError("{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}")).isTrue();
        assertThat(push.isDeadTokenError("{\"error\":{\"status\":\"UNAVAILABLE\"}}")).isFalse();
        assertThat(push.isDeadTokenError("not-json")).isFalse();
        assertThat(push.isDeadTokenError(null)).isFalse();
        assertThat(push.isDeadTokenError("")).isFalse();
    }

    @Test
    void construction_withBlankProjectId_failsFast() {
        CommunicationsChannelProperties props = props("  ");
        GoogleServiceAccountTokenProvider tp = stubTokenProvider();
        RestClient rc = RestClient.builder().build();
        ObjectMapper om = objectMapper;
        FakeRegistry reg = new FakeRegistry();
        assertThatThrownBy(() -> new FcmHttpPushSender(props, tp, rc, reg, om))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project-id");
    }
}
