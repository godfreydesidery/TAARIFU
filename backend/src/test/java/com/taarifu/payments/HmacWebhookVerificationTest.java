package com.taarifu.payments;

import com.taarifu.payments.infrastructure.adapter.MpesaGateway;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the webhook HMAC verification of the real mobile-money adapter (ADR-0015; PRD §23.5).
 *
 * <p>Responsibility: proves the callback authentication is <b>fail-closed</b> — a correct HMAC over the raw
 * body passes; a tampered body, a tampered signature, a missing signature, and a null body all fail. This is
 * the gate that stops a forged callback from ever reaching reconciliation. It also proves an active real
 * adapter with no HMAC secret fails fast (rather than silently accepting forgeries).</p>
 */
class HmacWebhookVerificationTest {

    private static final String SECRET = "test-hmac-secret-never-in-source";
    private MpesaGateway gateway;

    @BeforeEach
    void setUp() {
        // Real adapter with a configured base URL + secret; no network is touched by verifyCallbackSignature.
        PaymentsGatewayProperties config = new PaymentsGatewayProperties(
                "mpesa", "https://rail.example/api", SECRET, "X-Signature", 10, "TZS", Duration.ofSeconds(8));
        gateway = new MpesaGateway(config);
    }

    /** A correct HMAC-SHA256 hex signature over the exact body verifies. */
    @Test
    void validSignatureVerifies() {
        byte[] body = "{\"ref\":\"REF-1\",\"settled\":true}".getBytes(StandardCharsets.UTF_8);
        String sig = hmacHex(SECRET, body);

        assertThat(gateway.verifyCallbackSignature(body, sig)).isTrue();
    }

    /** A signature for a different body does NOT verify (tamper detection). */
    @Test
    void tamperedBodyFails() {
        byte[] body = "{\"ref\":\"REF-1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"ref\":\"REF-2\"}".getBytes(StandardCharsets.UTF_8);
        String sig = hmacHex(SECRET, body);

        assertThat(gateway.verifyCallbackSignature(tampered, sig)).isFalse();
    }

    /** A garbage / missing / null signature fails closed (no oracle, no exception). */
    @Test
    void missingOrGarbageSignatureFailsClosed() {
        byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(gateway.verifyCallbackSignature(body, null)).isFalse();
        assertThat(gateway.verifyCallbackSignature(body, "")).isFalse();
        assertThat(gateway.verifyCallbackSignature(body, "not-hex-zzzz")).isFalse();
        assertThat(gateway.verifyCallbackSignature(null, "00")).isFalse();
    }

    /** An active real adapter with no HMAC secret must fail fast at construction (no silent forgery gap). */
    @Test
    void blankSecretFailsFast() {
        PaymentsGatewayProperties noSecret = new PaymentsGatewayProperties(
                "mpesa", "https://rail.example/api", "  ", "X-Signature", 10, "TZS", Duration.ofSeconds(8));

        assertThatThrownBy(() -> new MpesaGateway(noSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hmac-secret");
    }

    /** Computes a lowercase hex HMAC-SHA256, mirroring the adapter's expectation. */
    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
