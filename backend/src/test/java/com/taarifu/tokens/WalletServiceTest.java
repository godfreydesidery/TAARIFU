package com.taarifu.tokens;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.tokens.application.service.WalletService;
import com.taarifu.tokens.domain.model.TokenReward;
import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import com.taarifu.tokens.domain.model.enums.RewardBehaviour;
import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import com.taarifu.tokens.domain.repository.TokenRewardRepository;
import com.taarifu.tokens.domain.repository.TokenTransactionRepository;
import com.taarifu.tokens.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WalletService} — idempotent grant/earn and the anti-farming earn cap (PRD §23.3,
 * §23.5). No database needed.
 *
 * <p>Responsibility: proves the credit side of the ledger is idempotent (a replayed grant credits nothing
 * twice) and that earning honours the per-behaviour cap (a farm cannot mint beyond it). These protect the
 * "balance = derived ledger" invariant and the positive-incentive (not paywall) stance.</p>
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository wallets;
    @Mock
    private TokenTransactionRepository ledger;
    @Mock
    private TokenRewardRepository rewards;
    @Mock
    private ClockPort clock;

    private WalletService service;

    private final UUID ownerId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new WalletService(wallets, ledger, rewards, clock);
        lenient().when(clock.now()).thenReturn(now);
    }

    /** A first-time grant appends a GRANT ledger entry and advances the cached balance. */
    @Test
    void grant_creditsAndAdvancesBalance() {
        Wallet wallet = walletWithId(1L);
        when(ledger.findByIdempotencyKey("grant-signup")).thenReturn(Optional.empty());
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.latestBalanceForWallet(1L)).thenReturn(Optional.of(0L));
        when(ledger.save(any(TokenTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenTransaction tx = service.grant(WalletOwnerType.USER, ownerId, 50, "SIGNUP_GRANT", "grant-signup");

        assertThat(tx.getType()).isEqualTo(TokenTransactionType.GRANT);
        assertThat(tx.getAmount()).isEqualTo(50);
        assertThat(tx.getBalanceAfter()).isEqualTo(50);
        assertThat(wallet.getCachedBalance()).isEqualTo(50);
        verify(wallets).save(wallet);
    }

    /** A replayed grant (same key) returns the existing entry and writes nothing new (no double-credit). */
    @Test
    void grant_isIdempotent() {
        Wallet wallet = walletWithId(2L);
        TokenTransaction existing = TokenTransaction.Builder
                .of(wallet, TokenTransactionType.GRANT, 50)
                .balanceAfter(50)
                .idempotencyKey("grant-dup")
                .build();
        when(ledger.findByIdempotencyKey("grant-dup")).thenReturn(Optional.of(existing));

        TokenTransaction tx = service.grant(WalletOwnerType.USER, ownerId, 50, "SIGNUP_GRANT", "grant-dup");

        assertThat(tx).isSameAs(existing);
        verify(ledger, never()).save(any());
        verify(wallets, never()).save(any());
    }

    /** A non-positive grant amount is a bad request (you cannot grant zero/negative). */
    @Test
    void grant_rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.grant(WalletOwnerType.USER, ownerId, 0, "BAD", "k"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /**
     * A settled purchase top-up appends a {@code PURCHASE} ledger entry of exactly the purchased amount and
     * advances the cached balance — the credit side of the mobile-money flow (ADR-0015; PRD §23.4).
     */
    @Test
    void purchaseTopUp_creditsPurchaseEntry() {
        Wallet wallet = walletWithId(7L);
        when(ledger.findByIdempotencyKey("credit-evt-1")).thenReturn(Optional.empty());
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.latestBalanceForWallet(7L)).thenReturn(Optional.of(0L));
        when(ledger.save(any(TokenTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenTransaction tx = service.purchaseTopUp(WalletOwnerType.USER, ownerId, 100,
                "MPESA-REF-9", "credit-evt-1");

        assertThat(tx.getType()).isEqualTo(TokenTransactionType.PURCHASE);
        assertThat(tx.getAmount()).isEqualTo(100);
        assertThat(tx.getBalanceAfter()).isEqualTo(100);
        // The settlement reference is recorded as the (non-PII) audit reason; ref entity type is PAYMENT.
        assertThat(tx.getReason()).contains("MPESA-REF-9");
        assertThat(tx.getRefEntityType()).isEqualTo("PAYMENT");
        assertThat(wallet.getCachedBalance()).isEqualTo(100);
    }

    /**
     * A redelivered settlement under the SAME credit key returns the existing entry and writes nothing new —
     * exactly-once credit on a duplicate/out-of-order mobile-money webhook (PRD §23.5, anti-fraud).
     */
    @Test
    void purchaseTopUp_isIdempotentOnReplay() {
        Wallet wallet = walletWithId(8L);
        TokenTransaction existing = TokenTransaction.Builder
                .of(wallet, TokenTransactionType.PURCHASE, 100)
                .balanceAfter(100)
                .idempotencyKey("credit-evt-dup")
                .build();
        when(ledger.findByIdempotencyKey("credit-evt-dup")).thenReturn(Optional.of(existing));

        TokenTransaction tx = service.purchaseTopUp(WalletOwnerType.USER, ownerId, 100,
                "MPESA-REF-DUP", "credit-evt-dup");

        assertThat(tx).isSameAs(existing);
        verify(ledger, never()).save(any());   // no second credit
        verify(wallets, never()).save(any());
    }

    /** A non-positive purchase amount is a bad request (you cannot buy zero/negative tokens). */
    @Test
    void purchaseTopUp_rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.purchaseTopUp(WalletOwnerType.USER, ownerId, 0, "REF", "k"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    /** Earning credits the configured reward when under the cap. */
    @Test
    void earn_creditsRewardUnderCap() {
        Wallet wallet = walletWithId(3L);
        TokenReward reward = new TokenReward(RewardBehaviour.RESOLUTION_CONFIRMED, 10, 5, QuotaPeriod.DAILY);
        when(rewards.findByBehaviourAndActiveTrue(RewardBehaviour.RESOLUTION_CONFIRMED))
                .thenReturn(Optional.of(reward));
        when(ledger.findByIdempotencyKey("earn-1")).thenReturn(Optional.empty());
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.countInWindow(eq(3L), eq(TokenTransactionType.EARN), eq("RESOLUTION_CONFIRMED"), any()))
                .thenReturn(0L);
        when(ledger.latestBalanceForWallet(3L)).thenReturn(Optional.of(0L));
        when(ledger.save(any(TokenTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenTransaction tx = service.earn(WalletOwnerType.USER, ownerId,
                RewardBehaviour.RESOLUTION_CONFIRMED, "REPORT", UUID.randomUUID(), "earn-1");

        assertThat(tx).isNotNull();
        assertThat(tx.getType()).isEqualTo(TokenTransactionType.EARN);
        assertThat(tx.getAmount()).isEqualTo(10);
    }

    /** Once the per-behaviour cap is reached, earning is a no-op (anti-farming). */
    @Test
    void earn_isCappedPerBehaviour() {
        Wallet wallet = walletWithId(4L);
        TokenReward reward = new TokenReward(RewardBehaviour.HELPFUL_CONTRIBUTION, 10, 2, QuotaPeriod.DAILY);
        when(rewards.findByBehaviourAndActiveTrue(RewardBehaviour.HELPFUL_CONTRIBUTION))
                .thenReturn(Optional.of(reward));
        when(ledger.findByIdempotencyKey("earn-capped")).thenReturn(Optional.empty());
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.countInWindow(eq(4L), eq(TokenTransactionType.EARN), eq("HELPFUL_CONTRIBUTION"), any()))
                .thenReturn(2L); // cap already reached

        TokenTransaction tx = service.earn(WalletOwnerType.USER, ownerId,
                RewardBehaviour.HELPFUL_CONTRIBUTION, null, null, "earn-capped");

        assertThat(tx).isNull();
        verify(ledger, never()).save(any());
    }

    /** No active reward configured for a behaviour → earning credits nothing (no-op). */
    @Test
    void earn_noConfigIsNoOp() {
        when(rewards.findByBehaviourAndActiveTrue(RewardBehaviour.REPUTATION_MILESTONE))
                .thenReturn(Optional.empty());

        TokenTransaction tx = service.earn(WalletOwnerType.USER, ownerId,
                RewardBehaviour.REPUTATION_MILESTONE, null, null, "earn-none");

        assertThat(tx).isNull();
        verify(ledger, never()).save(any());
    }

    private Wallet walletWithId(long id) {
        Wallet wallet = new Wallet(WalletOwnerType.USER, ownerId);
        try {
            var field = wallet.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(wallet, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return wallet;
    }
}
