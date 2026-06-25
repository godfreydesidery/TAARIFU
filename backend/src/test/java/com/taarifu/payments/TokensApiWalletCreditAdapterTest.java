package com.taarifu.payments;

import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.infrastructure.adapter.TokensApiWalletCreditAdapter;
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
 * Unit tests for {@link TokensApiWalletCreditAdapter} — the production {@code WalletCreditPort} that credits
 * a settled top-up to the token wallet through the published {@code tokens.api} port (ADR-0015; P0-2/P2-5).
 *
 * <p>Responsibility: prove the adapter now makes a <b>direct, typed</b> {@link TokenLedgerApi#topUp} call
 * (the reflective bridge is gone), maps payments' {@link WalletOwnerKind} 1:1 to the tokens
 * {@link WalletOwnerType}, and — the 🔒 D18 fence — its <b>only</b> cross-module effect is that one
 * convenience-credit call; it never reaches for a role/vote/weight method (there is none on the port).</p>
 */
@ExtendWith(MockitoExtension.class)
class TokensApiWalletCreditAdapterTest {

    @Mock
    private TokenLedgerApi tokenLedgerApi;

    private TokensApiWalletCreditAdapter adapter;

    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new TokensApiWalletCreditAdapter(tokenLedgerApi);
    }

    /** A USER credit maps to tokens' WalletOwnerType.USER and calls topUp once with the credit key. */
    @Test
    void creditPurchase_user_typedTopUpCall() {
        when(tokenLedgerApi.topUp(WalletOwnerType.USER, ownerId, 100, "credit-evt-1")).thenReturn(true);

        boolean credited = adapter.creditPurchase(WalletOwnerKind.USER, ownerId, 100, "credit-evt-1");

        assertThat(credited).isTrue();
        // 🔒 FENCE: the ONLY cross-module effect is exactly one convenience-credit topUp call.
        verify(tokenLedgerApi).topUp(eq(WalletOwnerType.USER), eq(ownerId), eq(100L), eq("credit-evt-1"));
        verifyNoMoreInteractions(tokenLedgerApi);
    }

    /** An ORGANIZATION credit maps to tokens' WalletOwnerType.ORGANIZATION (1:1 by name). */
    @Test
    void creditPurchase_organization_mapsOwnerType() {
        when(tokenLedgerApi.topUp(WalletOwnerType.ORGANIZATION, ownerId, 50, "credit-evt-2"))
                .thenReturn(true);

        boolean credited = adapter.creditPurchase(WalletOwnerKind.ORGANIZATION, ownerId, 50, "credit-evt-2");

        assertThat(credited).isTrue();
        verify(tokenLedgerApi).topUp(eq(WalletOwnerType.ORGANIZATION), eq(ownerId), eq(50L),
                eq("credit-evt-2"));
        verifyNoMoreInteractions(tokenLedgerApi);
    }

    /** The adapter relays the tokens-side outcome (false = idempotent replay already credited). */
    @Test
    void creditPurchase_relaysIdempotentReplayOutcome() {
        when(tokenLedgerApi.topUp(WalletOwnerType.USER, ownerId, 100, "credit-evt-3")).thenReturn(false);

        boolean credited = adapter.creditPurchase(WalletOwnerKind.USER, ownerId, 100, "credit-evt-3");

        assertThat(credited).isFalse();
        verify(tokenLedgerApi).topUp(eq(WalletOwnerType.USER), eq(ownerId), eq(100L), eq("credit-evt-3"));
    }
}
