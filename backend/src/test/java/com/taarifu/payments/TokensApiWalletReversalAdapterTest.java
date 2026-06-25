package com.taarifu.payments;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.infrastructure.adapter.TokensApiWalletReversalAdapter;
import com.taarifu.tokens.api.TokenLedgerApi;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokensApiWalletReversalAdapter} — the production {@code WalletReversalPort} that
 * reverses a refunded top-up from the token wallet through the published {@code tokens.api} port (ADR-0015
 * addendum: REFUND/VOID; the CENTRAL NEED now satisfied).
 *
 * <p>Responsibility: prove the adapter makes a <b>direct, typed</b> {@link TokenLedgerApi#refund} call (the
 * reflective bridge is gone), maps payments' {@link WalletOwnerKind} 1:1 to the tokens
 * {@link WalletOwnerType}, threads the (non-PII) reversal reason through, and — the 🔒 D18 fence — its
 * <b>only</b> cross-module effect is that one convenience-reversal call; it never reaches for a
 * role/vote/weight method (there is none on the port).</p>
 */
@ExtendWith(MockitoExtension.class)
class TokensApiWalletReversalAdapterTest {

    @Mock
    private TokenLedgerApi tokenLedgerApi;

    private TokensApiWalletReversalAdapter adapter;

    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new TokensApiWalletReversalAdapter(tokenLedgerApi);
    }

    /** A USER reversal maps to tokens' WalletOwnerType.USER and calls refund once with the reversal key + reason. */
    @Test
    void reverseTopUp_user_typedRefundCall() {
        when(tokenLedgerApi.refund(WalletOwnerType.USER, ownerId, 100, "rev-evt-1", "DUPLICATE_CHARGE"))
                .thenReturn(true);

        boolean reversed = adapter.reverseTopUp(WalletOwnerKind.USER, ownerId, 100, "rev-evt-1",
                "DUPLICATE_CHARGE");

        assertThat(reversed).isTrue();
        // 🔒 FENCE: the ONLY cross-module effect is exactly one convenience-reversal refund call.
        verify(tokenLedgerApi).refund(eq(WalletOwnerType.USER), eq(ownerId), eq(100L), eq("rev-evt-1"),
                eq("DUPLICATE_CHARGE"));
        verifyNoMoreInteractions(tokenLedgerApi);
    }

    /** An ORGANIZATION reversal maps to tokens' WalletOwnerType.ORGANIZATION (1:1 by name). */
    @Test
    void reverseTopUp_organization_mapsOwnerType() {
        when(tokenLedgerApi.refund(WalletOwnerType.ORGANIZATION, ownerId, 50, "rev-evt-2", "ADMIN"))
                .thenReturn(true);

        boolean reversed = adapter.reverseTopUp(WalletOwnerKind.ORGANIZATION, ownerId, 50, "rev-evt-2",
                "ADMIN");

        assertThat(reversed).isTrue();
        verify(tokenLedgerApi).refund(eq(WalletOwnerType.ORGANIZATION), eq(ownerId), eq(50L), eq("rev-evt-2"),
                eq("ADMIN"));
        verifyNoMoreInteractions(tokenLedgerApi);
    }

    /** The adapter relays the tokens-side outcome (false = idempotent replay, already reversed). */
    @Test
    void reverseTopUp_relaysIdempotentReplayOutcome() {
        when(tokenLedgerApi.refund(WalletOwnerType.USER, ownerId, 100, "rev-evt-3", "ADMIN"))
                .thenReturn(false);

        boolean reversed = adapter.reverseTopUp(WalletOwnerKind.USER, ownerId, 100, "rev-evt-3", "ADMIN");

        assertThat(reversed).isFalse();
        verify(tokenLedgerApi).refund(eq(WalletOwnerType.USER), eq(ownerId), eq(100L), eq("rev-evt-3"),
                eq("ADMIN"));
    }
}
