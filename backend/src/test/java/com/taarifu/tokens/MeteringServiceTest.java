package com.taarifu.tokens;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.tokens.application.service.MeteringService;
import com.taarifu.tokens.application.service.SpendOutcome;
import com.taarifu.tokens.domain.model.ActionCostPolicy;
import com.taarifu.tokens.domain.model.TokenTransaction;
import com.taarifu.tokens.domain.model.Wallet;
import com.taarifu.tokens.domain.model.WalletFreeQuotaState;
import com.taarifu.tokens.domain.model.enums.QuotaPeriod;
import com.taarifu.tokens.domain.model.enums.TokenTransactionType;
import com.taarifu.tokens.domain.model.enums.WalletOwnerType;
import com.taarifu.tokens.domain.repository.ActionCostPolicyRepository;
import com.taarifu.tokens.domain.repository.TokenTransactionRepository;
import com.taarifu.tokens.domain.repository.WalletFreeQuotaStateRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeteringService} — the fenced, free-quota-first metering engine (PRD §23.2, D18).
 *
 * <p>Responsibility: proves the load-bearing integrity behaviours of the token economy without a database:
 * (1) the <b>civic-integrity fence</b> — binding democratic action codes are rejected and never metered;
 * (2) free quota is consumed <b>before</b> tokens; (3) tokens are charged only once the free quota is
 * exhausted; (4) an insufficient balance is reported, never charged. These are the invariants the security
 * reviewer cares about; the fence test in particular <b>fails closed</b> if the guard is removed.</p>
 */
@ExtendWith(MockitoExtension.class)
class MeteringServiceTest {

    @Mock
    private WalletRepository wallets;
    @Mock
    private ActionCostPolicyRepository policies;
    @Mock
    private WalletFreeQuotaStateRepository quotaStates;
    @Mock
    private TokenTransactionRepository ledger;
    @Mock
    private ClockPort clock;

    private MeteringService metering;

    private final UUID ownerId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        metering = new MeteringService(wallets, policies, quotaStates, ledger, clock);
        lenient().when(clock.now()).thenReturn(now);
    }

    /**
     * THE FENCE (D18): tokens may never meter a binding democratic action. Each reserved code is rejected
     * with FORBIDDEN, and crucially <b>no</b> wallet/policy/ledger access happens — the path never even looks
     * at a balance. If this guard were removed, this test fails (signing a petition would become a spend).
     */
    @Test
    void spend_isHardRejectedForBindingDemocraticActions() {
        for (String binding : new String[]{"SIGN_PETITION", "RATE_REP", "VOTE_BINDING_POLL"}) {
            assertThatThrownBy(() -> metering.spend(WalletOwnerType.USER, ownerId, binding, "CITIZEN",
                    "PETITION", UUID.randomUUID(), "idem-" + binding))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
        // The fence rejects before touching any collaborator — a balance is never consulted for these.
        verify(wallets, never()).findForUpdate(any(), any());
        verify(ledger, never()).save(any());
        verify(policies, never()).findActiveDefault(anyString());
    }

    /** An action with no policy is unmetered: always allowed, free, with no ledger entry. */
    @Test
    void spend_unmeteredActionIsAlwaysFree() {
        Wallet wallet = walletWithId(1L);
        when(policies.findByActionCodeAndRoleNameAndActiveTrue("BROWSE", "CITIZEN")).thenReturn(Optional.empty());
        when(policies.findActiveDefault("BROWSE")).thenReturn(Optional.empty());
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.findByIdempotencyKey("idem-browse")).thenReturn(Optional.empty());
        when(ledger.latestBalanceForWallet(1L)).thenReturn(Optional.of(0L));

        SpendOutcome outcome = metering.spend(WalletOwnerType.USER, ownerId, "BROWSE", "CITIZEN",
                null, null, "idem-browse");

        assertThat(outcome.settledBy()).isEqualTo(SpendOutcome.Settlement.FREE_QUOTA);
        assertThat(outcome.tokensCharged()).isZero();
        verify(ledger, never()).save(any());
    }

    /** With free quota remaining, a metered action is settled FREE and increments the counter (no tokens). */
    @Test
    void spend_consumesFreeQuotaBeforeTokens() {
        Wallet wallet = walletWithId(2L);
        ActionCostPolicy policy = new ActionCostPolicy("FILE_REPORT", "CITIZEN", 5,
                QuotaPeriod.DAILY, 3); // 3 free/day, then 5 tokens
        WalletFreeQuotaState state = new WalletFreeQuotaState(wallet, "FILE_REPORT",
                QuotaPeriod.DAILY.windowStart(now)); // used 0 of 3

        when(ledger.findByIdempotencyKey("idem-file-1")).thenReturn(Optional.empty());
        when(policies.findByActionCodeAndRoleNameAndActiveTrue("FILE_REPORT", "CITIZEN"))
                .thenReturn(Optional.of(policy));
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.latestBalanceForWallet(2L)).thenReturn(Optional.of(100L));
        when(quotaStates.findByWalletAndActionCode(wallet, "FILE_REPORT")).thenReturn(Optional.of(state));

        SpendOutcome outcome = metering.spend(WalletOwnerType.USER, ownerId, "FILE_REPORT", "CITIZEN",
                "REPORT", UUID.randomUUID(), "idem-file-1");

        assertThat(outcome.settledBy()).isEqualTo(SpendOutcome.Settlement.FREE_QUOTA);
        assertThat(outcome.tokensCharged()).isZero();
        assertThat(outcome.freeRemaining()).isEqualTo(2); // consumed 1 of 3
        assertThat(state.getUsedCount()).isEqualTo(1);
        verify(quotaStates).save(state);
        verify(ledger, never()).save(any()); // no token movement on the free path
    }

    /** Once the free quota is exhausted, a sufficient balance is charged and a SPEND ledger row is written. */
    @Test
    void spend_chargesTokensWhenFreeQuotaExhausted() {
        Wallet wallet = walletWithId(3L);
        ActionCostPolicy policy = new ActionCostPolicy("FILE_REPORT", "CITIZEN", 5, QuotaPeriod.DAILY, 3);
        WalletFreeQuotaState exhausted = new WalletFreeQuotaState(wallet, "FILE_REPORT",
                QuotaPeriod.DAILY.windowStart(now));
        exhausted.incrementUsed();
        exhausted.incrementUsed();
        exhausted.incrementUsed(); // used 3 of 3 → exhausted

        when(ledger.findByIdempotencyKey("idem-file-4")).thenReturn(Optional.empty());
        when(policies.findByActionCodeAndRoleNameAndActiveTrue("FILE_REPORT", "CITIZEN"))
                .thenReturn(Optional.of(policy));
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.latestBalanceForWallet(3L)).thenReturn(Optional.of(100L));
        when(quotaStates.findByWalletAndActionCode(wallet, "FILE_REPORT")).thenReturn(Optional.of(exhausted));
        when(ledger.save(any(TokenTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        SpendOutcome outcome = metering.spend(WalletOwnerType.USER, ownerId, "FILE_REPORT", "CITIZEN",
                "REPORT", UUID.randomUUID(), "idem-file-4");

        assertThat(outcome.settledBy()).isEqualTo(SpendOutcome.Settlement.TOKENS);
        assertThat(outcome.tokensCharged()).isEqualTo(5);
        assertThat(outcome.balanceAfter()).isEqualTo(95);
        assertThat(wallet.getCachedBalance()).isEqualTo(95); // cache advanced with the ledger
        verify(ledger).save(any(TokenTransaction.class));
        verify(wallets).save(wallet);
    }

    /** Free quota exhausted AND balance below cost → INSUFFICIENT, and nothing is charged (free path stays). */
    @Test
    void spend_insufficientBalanceIsReportedNeverCharged() {
        Wallet wallet = walletWithId(4L);
        ActionCostPolicy policy = new ActionCostPolicy("BOOST_REPORT", "CITIZEN", 50, QuotaPeriod.DAILY, 0);
        WalletFreeQuotaState noFree = new WalletFreeQuotaState(wallet, "BOOST_REPORT",
                QuotaPeriod.DAILY.windowStart(now)); // 0 free configured

        when(ledger.findByIdempotencyKey("idem-boost")).thenReturn(Optional.empty());
        when(policies.findByActionCodeAndRoleNameAndActiveTrue("BOOST_REPORT", "CITIZEN"))
                .thenReturn(Optional.of(policy));
        when(wallets.findForUpdate(WalletOwnerType.USER, ownerId)).thenReturn(Optional.of(wallet));
        when(ledger.latestBalanceForWallet(4L)).thenReturn(Optional.of(10L)); // < 50
        when(quotaStates.findByWalletAndActionCode(wallet, "BOOST_REPORT")).thenReturn(Optional.of(noFree));

        SpendOutcome outcome = metering.spend(WalletOwnerType.USER, ownerId, "BOOST_REPORT", "CITIZEN",
                "REPORT", UUID.randomUUID(), "idem-boost");

        assertThat(outcome.settledBy()).isEqualTo(SpendOutcome.Settlement.INSUFFICIENT);
        assertThat(outcome.isAllowed()).isFalse();
        verify(ledger, never()).save(any());
        verify(wallets, never()).save(any());
    }

    /** A replayed metered attempt (same idempotency key) returns the prior outcome and never double-charges. */
    @Test
    void spend_isIdempotentOnReplay() {
        Wallet wallet = walletWithId(5L);
        TokenTransaction prior = TokenTransaction.Builder
                .of(wallet, TokenTransactionType.SPEND, 5)
                .balanceAfter(95)
                .actionCode("FILE_REPORT")
                .idempotencyKey("idem-replay")
                .build();
        when(ledger.findByIdempotencyKey("idem-replay")).thenReturn(Optional.of(prior));
        when(ledger.latestBalanceForWallet(5L)).thenReturn(Optional.of(95L));

        SpendOutcome outcome = metering.spend(WalletOwnerType.USER, ownerId, "FILE_REPORT", "CITIZEN",
                "REPORT", UUID.randomUUID(), "idem-replay");

        assertThat(outcome.settledBy()).isEqualTo(SpendOutcome.Settlement.TOKENS);
        assertThat(outcome.tokensCharged()).isEqualTo(5);
        verify(ledger, never()).save(any()); // no new entry on replay
    }

    /** A {@code Wallet} with a forced internal id, for stubbing ledger lookups keyed by wallet id. */
    private Wallet walletWithId(long id) {
        Wallet wallet = new Wallet(WalletOwnerType.USER, ownerId);
        setId(wallet, id);
        return wallet;
    }

    /** Reflectively sets the inherited BaseEntity id (DB-generated in production; forced here for unit tests). */
    private void setId(Wallet wallet, long id) {
        try {
            var field = wallet.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(wallet, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
