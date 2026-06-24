package com.taarifu.communications.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.communications.domain.port.PushSender;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FcmHttpPushSender} — proves the FCM HTTP v1 message body is built to the v1
 * contract and the no-token degradation holds, with <b>no real network and no Google credentials</b>
 * (CLAUDE.md §10).
 *
 * <p>Responsibility: the adapter is constructed with a stub {@link GoogleServiceAccountTokenProvider}
 * (never touching a credential file) and an unused mock-transport {@link RestClient}. The tests assert the
 * load-bearing contract: {@link FcmHttpPushSender#buildFcmMessage} produces
 * {@code {message:{token, notification:{title,body}, data:{deepLinkRef}}}} (the exact FCM v1 shape, PII-free),
 * a {@code send} in the current (registry-less) MVP returns {@link PushSender.PushResult#noDeviceToken()}
 * so the dispatcher falls back to SMS (EI-5), and a blank project id fails fast.</p>
 */
class FcmHttpPushSenderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private FcmHttpPushSender adapter(String projectId) {
        return new FcmHttpPushSender(props(projectId), stubTokenProvider(), RestClient.builder().build());
    }

    @Test
    void buildFcmMessage_matchesV1Contract_withDeepLinkData() {
        FcmHttpPushSender push = adapter("taarifu-prod");
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
        FcmHttpPushSender push = adapter("taarifu-prod");
        PushSender.PushMessage msg = new PushSender.PushMessage(
                "22222222-2222-2222-2222-222222222222", "Taarifu", "Habari", null, "idem-2");

        Map<String, Object> body = push.buildFcmMessage("tok", msg);

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) body.get("message");
        assertThat(message).doesNotContainKey("data");
    }

    @Test
    void send_withNoTokenRegistry_returnsNoDeviceToken_soDispatcherFallsBackToSms() {
        // MVP: no device-token registry yet → the spec's SMS-fallback signal (EI-5), not an error.
        PushSender.PushResult result = adapter("taarifu-prod").send(new PushSender.PushMessage(
                "33333333-3333-3333-3333-333333333333", "T", "B", null, "idem-3"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.noToken()).isTrue();
    }

    @Test
    void construction_withBlankProjectId_failsFast() {
        CommunicationsChannelProperties props = props("  ");
        GoogleServiceAccountTokenProvider tp = stubTokenProvider();
        RestClient rc = RestClient.builder().build();
        assertThatThrownBy(() -> new FcmHttpPushSender(props, tp, rc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project-id");
    }
}
