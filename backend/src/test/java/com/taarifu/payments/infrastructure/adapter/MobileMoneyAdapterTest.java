package com.taarifu.payments.infrastructure.adapter;

import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.domain.port.MobileMoneyGateway.CallbackResult;
import com.taarifu.payments.domain.port.MobileMoneyGateway.CollectionRequest;
import com.taarifu.payments.domain.port.MobileMoneyGateway.InitiationResult;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Per-rail unit tests for the four <b>real</b> Tanzanian mobile-money adapters (ADR-0015; PRD §23.5/§23.6,
 * §21 EI-20) — M-Pesa (Daraja), Tigo Pesa (Mixx), Airtel Money, HaloPesa.
 *
 * <p>Lives in the adapter package so it can use each adapter's package-private mock-transport test seam
 * (the {@code (config, RestClient)} constructor) — the same convention as
 * {@code communications ...adapter.HttpSmsGatewayTest}. With a {@link MockRestServiceServer} bound to each
 * adapter's {@link RestClient} (and <b>no real network</b>, CLAUDE.md §10), each nested class proves that
 * adapter's three load-bearing, genuinely-divergent behaviours:</p>
 * <ol>
 *   <li><b>Collection request shape</b> — the POST targets that rail's real path with that rail's real field
 *       names (Daraja {@code BusinessShortCode/PhoneNumber/AccountReference}; Mixx
 *       {@code referenceId/msisdn/amount}; Airtel nested {@code subscriber/transaction};
 *       HaloPesa {@code externalId/businessNumber}).</li>
 *   <li><b>Callback parse</b> — that rail's real callback body maps to {@code (providerRef, settled)} using
 *       that rail's success convention (Daraja {@code ResultCode==0}; Mixx {@code status:SUCCESS};
 *       Airtel {@code status.code:TS}; HaloPesa {@code responseCode:000}).</li>
 *   <li><b>Signature verify</b> — a correct HMAC over the raw body verifies; a tampered one fails closed
 *       (the shared, fail-closed gate; PRD §23.5).</li>
 * </ol>
 *
 * <p>Degradation is also asserted once: a transport 5xx degrades initiation to not-accepted <b>without
 * throwing</b> (EI-20). The logging stub remains the wired default in {@code dev}/{@code test} — these tests
 * construct the real adapters directly via their package-private test seam, touching no Spring context and no
 * external rail.</p>
 */
class MobileMoneyAdapterTest {

    private static final String BASE = "https://rail.example/api";
    private static final String SECRET = "test-hmac-secret-never-in-source";
    private static final String MSISDN = "+255712345678";

    /** Config for a real adapter with the given merchant id; no real network is ever touched. */
    private static PaymentsGatewayProperties config(String provider, String merchantId) {
        return new PaymentsGatewayProperties(
                provider, BASE, SECRET, "X-Signature", 10, "TZS", Duration.ofSeconds(5), merchantId);
    }

    /** A correct lowercase-hex HMAC-SHA256 over the body, mirroring the adapter's verification. */
    private static String hmacHex(byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CollectionRequest request() {
        return new CollectionRequest(50_000L, "TZS", MSISDN, "idem-key-1");
    }

    // ===================================================================================================
    // M-PESA (Vodacom / Daraja STK push)
    // ===================================================================================================

    @Nested
    class Mpesa {

        @Test
        void collection_postsDarajaStkPush_withShortCodeAndAccountReference() {
            RestClient.Builder b = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
            MpesaGateway gateway = new MpesaGateway(config("mpesa", "174379"), b.build());

            server.expect(requestTo(BASE + "/mpesa/stkpush/v1/processrequest"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.BusinessShortCode").value("174379"))
                    .andExpect(jsonPath("$.TransactionType").value("CustomerPayBillOnline"))
                    .andExpect(jsonPath("$.Amount").value(50000))
                    .andExpect(jsonPath("$.PhoneNumber").value(MSISDN))
                    .andExpect(jsonPath("$.AccountReference").value("idem-key-1"))
                    .andRespond(withSuccess("{\"CheckoutRequestID\":\"ws_CO_123\"}",
                            MediaType.APPLICATION_JSON));

            InitiationResult result = gateway.initiateCollection(request());

            assertThat(result.accepted()).isTrue();
            assertThat(result.providerRef()).isEqualTo("ws_CO_123");
            server.verify();
        }

        @Test
        void callback_nestedStkCallback_resultCodeZero_isSettled() {
            MpesaGateway gateway = new MpesaGateway(config("mpesa", "174379"));
            byte[] body = ("{\"Body\":{\"stkCallback\":{\"CheckoutRequestID\":\"ws_CO_123\","
                    + "\"ResultCode\":0,\"ResultDesc\":\"ok\"}}}").getBytes(StandardCharsets.UTF_8);

            CallbackResult parsed = gateway.parseCallback(body);

            assertThat(parsed.providerRef()).isEqualTo("ws_CO_123");
            assertThat(parsed.settled()).isTrue();
        }

        @Test
        void callback_nestedStkCallback_nonZeroResultCode_isNotSettled() {
            MpesaGateway gateway = new MpesaGateway(config("mpesa", "174379"));
            byte[] body = ("{\"Body\":{\"stkCallback\":{\"CheckoutRequestID\":\"ws_CO_9\","
                    + "\"ResultCode\":1032,\"ResultDesc\":\"cancelled\"}}}").getBytes(StandardCharsets.UTF_8);

            CallbackResult parsed = gateway.parseCallback(body);

            assertThat(parsed.providerRef()).isEqualTo("ws_CO_9");
            assertThat(parsed.settled()).isFalse();
        }

        @Test
        void signature_validVerifies_tamperedFails() {
            MpesaGateway gateway = new MpesaGateway(config("mpesa", "174379"));
            byte[] body = "{\"CheckoutRequestID\":\"ws_CO_123\"}".getBytes(StandardCharsets.UTF_8);
            byte[] tampered = "{\"CheckoutRequestID\":\"ws_CO_X\"}".getBytes(StandardCharsets.UTF_8);
            String sig = hmacHex(body);

            assertThat(gateway.verifyCallbackSignature(body, sig)).isTrue();
            assertThat(gateway.verifyCallbackSignature(tampered, sig)).isFalse();
        }
    }

    // ===================================================================================================
    // TIGO PESA (Mixx by Yas)
    // ===================================================================================================

    @Nested
    class TigoPesa {

        @Test
        void collection_postsMixxPushBillpay_withReferenceAndMerchant() {
            RestClient.Builder b = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
            TigoPesaGateway gateway = new TigoPesaGateway(config("tigopesa", "MERCHANT-9"), b.build());

            server.expect(requestTo(BASE + "/push-billpay"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.referenceId").value("idem-key-1"))
                    .andExpect(jsonPath("$.msisdn").value(MSISDN))
                    .andExpect(jsonPath("$.amount").value(50000))
                    .andExpect(jsonPath("$.merchantAccountNumber").value("MERCHANT-9"))
                    .andRespond(withSuccess("{\"referenceId\":\"idem-key-1\"}", MediaType.APPLICATION_JSON));

            InitiationResult result = gateway.initiateCollection(request());

            assertThat(result.accepted()).isTrue();
            assertThat(result.providerRef()).isEqualTo("idem-key-1");
            server.verify();
        }

        @Test
        void callback_statusSuccess_isSettled_statusFailed_isNot() {
            TigoPesaGateway gateway = new TigoPesaGateway(config("tigopesa", "M"));

            CallbackResult ok = gateway.parseCallback(
                    "{\"referenceId\":\"R1\",\"status\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8));
            CallbackResult no = gateway.parseCallback(
                    "{\"referenceId\":\"R2\",\"status\":\"FAILED\"}".getBytes(StandardCharsets.UTF_8));

            assertThat(ok.providerRef()).isEqualTo("R1");
            assertThat(ok.settled()).isTrue();
            assertThat(no.providerRef()).isEqualTo("R2");
            assertThat(no.settled()).isFalse();
        }

        @Test
        void signature_validVerifies_tamperedFails() {
            TigoPesaGateway gateway = new TigoPesaGateway(config("tigopesa", "M"));
            byte[] body = "{\"referenceId\":\"R1\"}".getBytes(StandardCharsets.UTF_8);
            assertThat(gateway.verifyCallbackSignature(body, hmacHex(body))).isTrue();
            assertThat(gateway.verifyCallbackSignature("{\"referenceId\":\"R2\"}"
                    .getBytes(StandardCharsets.UTF_8), hmacHex(body))).isFalse();
        }
    }

    // ===================================================================================================
    // AIRTEL MONEY
    // ===================================================================================================

    @Nested
    class AirtelMoney {

        @Test
        void collection_postsNestedSubscriberTransaction() {
            RestClient.Builder b = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
            AirtelMoneyGateway gateway = new AirtelMoneyGateway(config("airtelmoney", null), b.build());

            server.expect(requestTo(BASE + "/merchant/v1/payments/"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.subscriber.msisdn").value(MSISDN))
                    .andExpect(jsonPath("$.subscriber.currency").value("TZS"))
                    .andExpect(jsonPath("$.transaction.amount").value(50000))
                    .andExpect(jsonPath("$.transaction.id").value("idem-key-1"))
                    .andRespond(withSuccess("{\"data\":{\"transaction\":{\"id\":\"AIRTEL-77\"}}}",
                            MediaType.APPLICATION_JSON));

            InitiationResult result = gateway.initiateCollection(request());

            assertThat(result.accepted()).isTrue();
            assertThat(result.providerRef()).isEqualTo("AIRTEL-77");
            server.verify();
        }

        @Test
        void callback_nestedStatusCodeTS_isSettled_TF_isNot() {
            AirtelMoneyGateway gateway = new AirtelMoneyGateway(config("airtelmoney", null));

            CallbackResult ok = gateway.parseCallback(("{\"transaction\":{\"id\":\"AIRTEL-77\","
                    + "\"status\":{\"code\":\"TS\"}}}").getBytes(StandardCharsets.UTF_8));
            CallbackResult no = gateway.parseCallback(("{\"transaction\":{\"id\":\"AIRTEL-78\","
                    + "\"status\":{\"code\":\"TF\"}}}").getBytes(StandardCharsets.UTF_8));

            assertThat(ok.providerRef()).isEqualTo("AIRTEL-77");
            assertThat(ok.settled()).isTrue();
            assertThat(no.providerRef()).isEqualTo("AIRTEL-78");
            assertThat(no.settled()).isFalse();
        }

        @Test
        void signature_validVerifies_tamperedFails() {
            AirtelMoneyGateway gateway = new AirtelMoneyGateway(config("airtelmoney", null));
            byte[] body = "{\"transaction\":{\"id\":\"AIRTEL-77\"}}".getBytes(StandardCharsets.UTF_8);
            assertThat(gateway.verifyCallbackSignature(body, hmacHex(body))).isTrue();
            assertThat(gateway.verifyCallbackSignature("{\"x\":1}".getBytes(StandardCharsets.UTF_8),
                    hmacHex(body))).isFalse();
        }
    }

    // ===================================================================================================
    // HALOPESA (Halotel)
    // ===================================================================================================

    @Nested
    class HaloPesa {

        @Test
        void collection_postsPushPayment_withExternalIdAndBusinessNumber() {
            RestClient.Builder b = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
            HaloPesaGateway gateway = new HaloPesaGateway(config("halopesa", "BIZ-1"), b.build());

            server.expect(requestTo(BASE + "/api/PushPayment"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.externalId").value("idem-key-1"))
                    .andExpect(jsonPath("$.msisdn").value(MSISDN))
                    .andExpect(jsonPath("$.amount").value(50000))
                    .andExpect(jsonPath("$.businessNumber").value("BIZ-1"))
                    .andRespond(withSuccess("{\"transactionId\":\"HALO-55\"}", MediaType.APPLICATION_JSON));

            InitiationResult result = gateway.initiateCollection(request());

            assertThat(result.accepted()).isTrue();
            assertThat(result.providerRef()).isEqualTo("HALO-55");
            server.verify();
        }

        @Test
        void callback_responseCode000_isSettled_nonZero_isNot() {
            HaloPesaGateway gateway = new HaloPesaGateway(config("halopesa", "BIZ-1"));

            CallbackResult ok = gateway.parseCallback(
                    "{\"externalId\":\"idem-key-1\",\"responseCode\":\"000\"}".getBytes(StandardCharsets.UTF_8));
            CallbackResult no = gateway.parseCallback(
                    "{\"externalId\":\"idem-key-2\",\"responseCode\":\"114\"}".getBytes(StandardCharsets.UTF_8));

            assertThat(ok.providerRef()).isEqualTo("idem-key-1");
            assertThat(ok.settled()).isTrue();
            assertThat(no.settled()).isFalse();
        }

        @Test
        void signature_validVerifies_tamperedFails() {
            HaloPesaGateway gateway = new HaloPesaGateway(config("halopesa", "BIZ-1"));
            byte[] body = "{\"externalId\":\"idem-key-1\"}".getBytes(StandardCharsets.UTF_8);
            assertThat(gateway.verifyCallbackSignature(body, hmacHex(body))).isTrue();
            assertThat(gateway.verifyCallbackSignature("{\"externalId\":\"other\"}"
                    .getBytes(StandardCharsets.UTF_8), hmacHex(body))).isFalse();
        }
    }

    // ===================================================================================================
    // Shared degradation contract (EI-20): a transport failure never throws on the citizen path.
    // ===================================================================================================

    @Test
    void initiation_onTransportError_degradesToNotAccepted_withoutThrowing() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        MpesaGateway gateway = new MpesaGateway(config("mpesa", "174379"), b.build());
        server.expect(requestTo(BASE + "/mpesa/stkpush/v1/processrequest")).andRespond(withServerError());

        MobileMoneyGateway.InitiationResult result = gateway.initiateCollection(request());

        assertThat(result.accepted()).isFalse();
        assertThat(result.providerRef()).isNull();
        server.verify();
    }
}
