package com.taarifu.payments;

import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.payments.application.service.ReconciliationService;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.domain.port.WalletCreditPort;
import com.taarifu.payments.domain.repository.TopUpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReconciliationService} — the heart of ADR-0015: provider-verified, idempotent
 * settlement → wallet credit, and the civic-integrity fence (D18). No database needed.
 *
 * <p>Responsibility: proves the integrity invariants that must never regress —
 * <ul>
 *   <li><b>happy path:</b> a PENDING top-up whose settlement the provider confirms is credited once,
 *       transitions to SUCCEEDED, and emits {@code TopUpSucceeded};</li>
 *   <li><b>never-trust-the-callback (PRD §23.5):</b> if {@code verifySettled} is false, NO credit and NO
 *       SUCCEEDED transition;</li>
 *   <li><b>idempotent (PRD §23.5):</b> a redelivered callback on an already-SUCCEEDED row credits nothing
 *       again;</li>
 *   <li><b>🔒 fence (D18):</b> the only effect of a settlement is a convenience top-up via
 *       {@link WalletCreditPort#creditPurchase} — the credit amount equals the purchased token amount and
 *       there is no other cross-module call (no democratic-weight path exists in the collaborator set).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private TopUpRepository topUps;
    @Mock
    private MobileMoneyGateway gateway;
    @Mock
    private WalletCreditPort walletCredit;
    @Mock
    private OutboxWriter outbox;

    private ReconciliationService service;

    private final UUID buyerId = UUID.randomUUID();
    private static final MobileMoneyProvider PROVIDER = MobileMoneyProvider.MPESA;
    private static final String REF = "MPESA-REF-123";

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(topUps, gateway, walletCredit, outbox);
    }

    /** A provider-confirmed settlement credits the wallet once, sets SUCCEEDED, and emits the event. */
    @Test
    void reconcile_creditsOnceOnVerifiedSettlement() {
        TopUp pending = pendingTopUp(100);
        when(topUps.findForUpdateByProviderRef(PROVIDER, REF)).thenReturn(Optional.of(pending));
        when(gateway.verifySettled(REF)).thenReturn(true);
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));

        TopUp result = service.reconcile(PROVIDER, REF);

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.SUCCEEDED);
        // 🔒 FENCE: the ONE effect is a convenience top-up of exactly the purchased token amount.
        verify(walletCredit).creditPurchase(eq(WalletOwnerKind.USER), eq(buyerId), eq(100L), anyString());
        verify(outbox).append(any(EventEnvelope.class));
    }

    /** Never-trust-the-callback: an unverified settlement credits nothing and leaves the row PENDING. */
    @Test
    void reconcile_doesNotCreditWhenProviderNotSettled() {
        TopUp pending = pendingTopUp(100);
        when(topUps.findForUpdateByProviderRef(PROVIDER, REF)).thenReturn(Optional.of(pending));
        when(gateway.verifySettled(REF)).thenReturn(false); // provider does NOT confirm

        TopUp result = service.reconcile(PROVIDER, REF);

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.PENDING);
        verifyNoInteractions(walletCredit);
        verify(outbox, never()).append(any());
    }

    /** A redelivered callback on an already-SUCCEEDED row is a no-op (idempotent — no double-credit). */
    @Test
    void reconcile_isIdempotentOnAlreadySucceeded() {
        TopUp succeeded = pendingTopUp(100);
        succeeded.markSucceeded(UUID.randomUUID());
        when(topUps.findForUpdateByProviderRef(PROVIDER, REF)).thenReturn(Optional.of(succeeded));

        TopUp result = service.reconcile(PROVIDER, REF);

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.SUCCEEDED);
        verifyNoInteractions(walletCredit);   // no second credit
        verify(gateway, never()).verifySettled(anyString());
        verify(outbox, never()).append(any());
    }

    /** An unknown/not-yet-visible provider reference is acknowledged and changes nothing. */
    @Test
    void reconcile_unknownReferenceIsNoOp() {
        when(topUps.findForUpdateByProviderRef(PROVIDER, REF)).thenReturn(Optional.empty());

        TopUp result = service.reconcile(PROVIDER, REF);

        assertThat(result).isNull();
        verifyNoInteractions(walletCredit);
        verify(outbox, never()).append(any());
    }

    /**
     * Two deliveries of the same settlement credit the wallet with the SAME idempotency key — so the ledger
     * (keyed on that id) credits exactly once even if the second delivery slipped past the terminal guard.
     */
    @Test
    void reconcile_usesStableCreditKeyPerTopUp() {
        TopUp pending = pendingTopUp(50);
        when(topUps.findForUpdateByProviderRef(PROVIDER, REF)).thenReturn(Optional.of(pending));
        when(gateway.verifySettled(REF)).thenReturn(true);
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reconcile(PROVIDER, REF);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(walletCredit, times(1))
                .creditPurchase(any(), any(), anyLong(), keyCaptor.capture());
        // The credit key is derived deterministically from the top-up's public id (stable across retries).
        UUID expected = UUID.nameUUIDFromBytes(("topup-credit:" + pending.getPublicId()).getBytes());
        assertThat(keyCaptor.getValue()).isEqualTo(expected.toString());
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /** Builds a PENDING top-up with a set public id + provider reference (no DB). */
    private TopUp pendingTopUp(long tokens) {
        TopUp t = new TopUp(buyerId, WalletOwnerKind.USER, PROVIDER, tokens * 100, tokens, "TZS", "idem-"
                + UUID.randomUUID());
        setField(t, "publicId", UUID.randomUUID());
        t.markPending(REF);
        return t;
    }

    /** Reflectively sets a BaseEntity/entity field (e.g. {@code publicId}) for a no-DB unit fixture. */
    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
