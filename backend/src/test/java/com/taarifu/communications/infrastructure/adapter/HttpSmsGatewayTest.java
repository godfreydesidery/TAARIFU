package com.taarifu.communications.infrastructure.adapter;

import com.taarifu.communications.domain.port.SmsGateway;
import com.taarifu.communications.infrastructure.config.CommunicationsChannelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link HttpSmsGateway} — proves the aggregator submit request is built correctly and the
 * HTTP outcome is mapped to the port result, with <b>no real network</b> (CLAUDE.md §10).
 *
 * <p>Responsibility: a {@link MockRestServiceServer} bound to the adapter's {@link RestClient} asserts the
 * load-bearing contract — the POST targets the configured submit URL with the API key on the configured
 * auth header, the JSON body carries {@code to/from/text/reference} (the sender-id as {@code from}, the
 * idempotency key as {@code reference}), a 2xx maps to {@code accepted}, and a 5xx degrades to
 * {@code failed} <b>without throwing</b> (EI-3). No real aggregator, no credentials.</p>
 */
class HttpSmsGatewayTest {

    private static final String SUBMIT_URL = "https://sms.example/submit";

    private CommunicationsChannelProperties propsWith(String authHeader) {
        CommunicationsChannelProperties.Sms sms = new CommunicationsChannelProperties.Sms(
                "http", SUBMIT_URL, "TAARIFU", "secret-key", authHeader, Duration.ofSeconds(5));
        return new CommunicationsChannelProperties(sms, null, null);
    }

    /** Builds the adapter over a mock-transport RestClient and returns the bound mock server. */
    private record Fixture(HttpSmsGateway gateway, MockRestServiceServer server) {
    }

    private Fixture fixture(String authHeader) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // The adapter under test uses THIS mock-transport RestClient via the package-visible test seam
        // constructor, so the request URL/headers/body are asserted with no real network.
        HttpSmsGateway gateway = new HttpSmsGateway(propsWith(authHeader), builder.build());
        return new Fixture(gateway, server);
    }

    @Test
    void send_postsAggregatorSubmit_andMapsSuccessToAccepted() {
        Fixture f = fixture("Authorization");
        f.server().expect(requestTo(SUBMIT_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.to").value("+255712345678"))
                .andExpect(jsonPath("$.from").value("TAARIFU"))
                .andExpect(jsonPath("$.text").value("Msimbo wako ni 123456"))
                .andExpect(jsonPath("$.reference").value("otp-key-1"))
                .andRespond(withSuccess("PROVIDER-MSG-9", MediaType.TEXT_PLAIN));

        SmsGateway.SmsSendResult result = f.gateway().send(new SmsGateway.SmsMessage(
                "+255712345678", "Msimbo wako ni 123456", "SIGNUP_OTP", "otp-key-1"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("PROVIDER-MSG-9");
        assertThat(result.reason()).isNull();
        f.server().verify();
    }

    @Test
    void send_withCustomAuthHeader_sendsRawKey_notBearer() {
        Fixture f = fixture("X-API-Key");
        f.server().expect(requestTo(SUBMIT_URL))
                .andExpect(header("X-API-Key", "secret-key"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        SmsGateway.SmsSendResult result = f.gateway().send(new SmsGateway.SmsMessage(
                "+255712345678", "habari", "NOTIFICATION", "key-2"));

        // Blank provider body falls back to the idempotency key as the message id.
        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("key-2");
        f.server().verify();
    }

    @Test
    void send_onServerError_degradesToFailed_withoutThrowing() {
        Fixture f = fixture("Authorization");
        f.server().expect(requestTo(SUBMIT_URL)).andRespond(withServerError());

        SmsGateway.SmsSendResult result = f.gateway().send(new SmsGateway.SmsMessage(
                "+255712345678", "habari", "NOTIFICATION", "key-3"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("SMS_SUBMIT_FAILED");
        // Reason carries no PII / no response body.
        assertThat(result.reason()).doesNotContain("+255");
        f.server().verify();
    }

    @Test
    void construction_withBlankSubmitUrl_failsFast() {
        CommunicationsChannelProperties.Sms noUrl = new CommunicationsChannelProperties.Sms(
                "http", "  ", "TAARIFU", "k", null, null);
        CommunicationsChannelProperties props = new CommunicationsChannelProperties(noUrl, null, null);
        assertThatThrownBy(() -> new HttpSmsGateway(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submit-url");
    }

    @Test
    void construction_withBlankSenderId_failsFast() {
        CommunicationsChannelProperties.Sms noSender = new CommunicationsChannelProperties.Sms(
                "http", SUBMIT_URL, "  ", "k", null, null);
        CommunicationsChannelProperties props = new CommunicationsChannelProperties(noSender, null, null);
        assertThatThrownBy(() -> new HttpSmsGateway(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sender-id");
    }
}
