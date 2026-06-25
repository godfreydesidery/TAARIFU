package com.taarifu.tokens;

import com.taarifu.tokens.api.TokenLedgerApi;
import com.taarifu.tokens.application.service.MeteringService;
import com.taarifu.tokens.application.service.TokenLedgerApiImpl;
import com.taarifu.tokens.application.service.WalletService;
import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenLedgerApiImpl} — the published {@link TokenLedgerApi} seam other modules wire
 * to (M17, D18; ADR-0015 §4). The {@code payments} module calls {@link TokenLedgerApi#topUp} on a SUCCEEDED
 * settlement, so this test pins both the typed delegation and the civic-integrity fence.
 *
 * <p>Responsibility: prove (a) {@code topUp} delegates a fence-safe {@code PURCHASE} credit to
 * {@link WalletService#purchaseTopUp} idempotently on the payment reference, and (b) the published API
 * surface exposes <b>no</b> balance-read / role / vote / weight method a binding democratic action could
 * abuse — the fence is the SHAPE of the contract, asserted reflectively so it fails closed if a forbidden
 * method is ever added.</p>
 */
@ExtendWith(MockitoExtension.class)
class TokenLedgerApiImplTest {

    @Mock
    private MeteringService meteringService;
    @Mock
    private WalletService walletService;

    private TokenLedgerApiImpl api;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        api = new TokenLedgerApiImpl(meteringService, walletService);
    }

    /** {@code topUp} delegates a PURCHASE credit, idempotent on the payment reference, and never meters. */
    @Test
    void topUp_delegatesFenceSafePurchaseCredit() {
        TokenTransaction credited = TokenTransaction.Builder
                .of(new Wallet(WalletOwnerType.USER, accountId), TokenTransactionType.PURCHASE, 100)
                .balanceAfter(100)
                .idempotencyKey("PAY-REF-1")
                .build();
        // The reference is BOTH the audit reason and the idempotency key (exactly-once on webhook replay).
        when(walletService.purchaseTopUp(WalletOwnerType.USER, accountId, 100, "PAY-REF-1", "PAY-REF-1"))
                .thenReturn(credited);

        boolean result = api.topUp(WalletOwnerType.USER, accountId, 100, "PAY-REF-1");

        assertThat(result).isTrue();
        verify(walletService).purchaseTopUp(eq(WalletOwnerType.USER), eq(accountId), eq(100L),
                eq("PAY-REF-1"), eq("PAY-REF-1"));
        // 🔒 FENCE: a top-up never touches the metering (spend) side — no balance is read for any action.
        verifyNoInteractions(meteringService);
    }

    /**
     * 🔒 FENCE (D18, PRD §23.5): the published {@link TokenLedgerApi} contract exposes ONLY the
     * convenience-credit/metering doors — there is NO method through which a binding democratic action could
     * read a balance or buy democratic weight (no {@code balance}/{@code role}/{@code vote}/{@code grantRole}
     * style method). This test fails closed the moment such a method is added to the published surface.
     */
    @Test
    void publishedApi_exposesNoDemocraticWeightOrBalanceMethod() {
        for (Method m : TokenLedgerApi.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertThat(name)
                    .as("TokenLedgerApi must expose no balance/role/vote/weight method (D18 fence): %s",
                            m.getName())
                    .doesNotContain("balance")
                    .doesNotContain("role")
                    .doesNotContain("vote")
                    .doesNotContain("weight")
                    .doesNotContain("sign")
                    .doesNotContain("rate");
        }
        // The contract is exactly: meter (convenience spend), reward (capped earn), topUp (purchase credit).
        assertThat(TokenLedgerApi.class.getDeclaredMethods()).hasSize(3);
    }
}
