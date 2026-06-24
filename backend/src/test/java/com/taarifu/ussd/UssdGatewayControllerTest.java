package com.taarifu.ussd;

import com.taarifu.common.domain.port.CryptoPort;
import com.taarifu.common.security.UssdGatewayRateLimiter;
import com.taarifu.ussd.api.controller.UssdGatewayController;
import com.taarifu.ussd.api.dto.UssdGatewayRequest;
import com.taarifu.ussd.api.dto.UssdGatewayResponse;
import com.taarifu.ussd.application.service.UssdMenuMachine;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UssdGatewayController} — per-MSISDN anti-automation on the open webhook
 * (wave2-review P2-1, THREAT-MODEL TB-3).
 *
 * <p>Responsibility: proves that the controller (a) hashes the MSISDN before touching the limiter (no raw
 * PII as a limiter key, S-4), (b) returns a plain {@code END} throttle line and <b>does not invoke the
 * state machine</b> when the per-MSISDN turn cap or the first-hit new-session cap trips, and (c) delegates
 * to the machine when admitted. The aggregator-secret check is covered by
 * {@link UssdGatewaySecretFilterTest}; here we isolate the rate-limit behaviour with fakes/mocks.</p>
 */
class UssdGatewayControllerTest {

    /** Trivial blind-index fake: deterministic, reversible-looking but distinct per input (no real key needed). */
    private static final CryptoPort CRYPTO = new CryptoPort() {
        @Override public String encrypt(String plaintext) { return plaintext; }
        @Override public String decrypt(String ciphertext) { return ciphertext; }
        @Override public String blindIndex(String value) { return "bi:" + value; }
    };

    private static final String MSISDN = "+255712345678";
    private static final String SID = "sess-1";

    /** Configurable fake limiter recording the keys it was asked about. */
    private static final class FakeLimiter implements UssdGatewayRateLimiter {
        private boolean allowTurn = true;
        private boolean allowNew = true;
        private final Set<String> turnKeys = new HashSet<>();
        private final Set<String> newKeys = new HashSet<>();

        @Override public boolean allowSessionTurn(String msisdnHash) {
            turnKeys.add(msisdnHash);
            return allowTurn;
        }

        @Override public boolean allowNewSession(String msisdnHash) {
            newKeys.add(msisdnHash);
            return allowNew;
        }
    }

    @Test
    void admittedRequest_delegatesToTheMachine() {
        UssdMenuMachine machine = mock(UssdMenuMachine.class);
        when(machine.handle(any())).thenReturn(UssdGatewayResponse.con("Chagua lugha:"));
        FakeLimiter limiter = new FakeLimiter();
        UssdGatewayController controller = new UssdGatewayController(machine, limiter, CRYPTO);

        String out = controller.gateway(new UssdGatewayRequest(SID, MSISDN, "*149#", "1"));

        assertThat(out).isEqualTo("CON Chagua lugha:");
        verify(machine).handle(any());
        // The limiter key is the HASHED MSISDN, never the raw number (S-4).
        assertThat(limiter.turnKeys).containsExactly("bi:" + MSISDN);
    }

    @Test
    void turnCapTripped_returnsEnd_andDoesNotInvokeMachine() {
        UssdMenuMachine machine = mock(UssdMenuMachine.class);
        FakeLimiter limiter = new FakeLimiter();
        limiter.allowTurn = false;
        UssdGatewayController controller = new UssdGatewayController(machine, limiter, CRYPTO);

        String out = controller.gateway(new UssdGatewayRequest(SID, MSISDN, "*149#", "1"));

        assertThat(out).startsWith("END ");
        verify(machine, never()).handle(any());
    }

    @Test
    void firstHit_consultsNewSessionCap_andTrippingItReturnsEnd() {
        UssdMenuMachine machine = mock(UssdMenuMachine.class);
        FakeLimiter limiter = new FakeLimiter();
        limiter.allowNew = false; // account-creation cap exhausted for this MSISDN
        UssdGatewayController controller = new UssdGatewayController(machine, limiter, CRYPTO);

        // Empty text = first hit of a fresh dialogue (the account-creation trigger).
        String out = controller.gateway(new UssdGatewayRequest(SID, MSISDN, "*149#", ""));

        assertThat(out).startsWith("END ");
        verify(machine, never()).handle(any());
        assertThat(limiter.newKeys).containsExactly("bi:" + MSISDN);
    }

    @Test
    void midDialogueHit_doesNotConsultNewSessionCap() {
        UssdMenuMachine machine = mock(UssdMenuMachine.class);
        when(machine.handle(any())).thenReturn(UssdGatewayResponse.con("Menyu kuu"));
        FakeLimiter limiter = new FakeLimiter();
        limiter.allowNew = false; // would block, but a mid-dialogue turn must not consult it
        UssdGatewayController controller = new UssdGatewayController(machine, limiter, CRYPTO);

        // Non-empty text = a continuing dialogue, not a fresh account-creation trigger.
        String out = controller.gateway(new UssdGatewayRequest(SID, MSISDN, "*149#", "1*1"));

        assertThat(out).isEqualTo("CON Menyu kuu");
        assertThat(limiter.newKeys).as("new-session cap not consulted mid-dialogue").isEmpty();
        verify(machine).handle(any());
    }
}
