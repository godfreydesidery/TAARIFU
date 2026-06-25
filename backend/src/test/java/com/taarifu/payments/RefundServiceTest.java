package com.taarifu.payments;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.payments.application.service.RefundService;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.WalletReversalPort;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Unit tests for {@link RefundService} — the REFUND/VOID half of ADR-0015 (addendum): idempotent,
 * fence-safe reversal of a settled top-up, and void of an un-settled one. No database needed.
 *
 * <p>Responsibility: proves the integrity invariants that must never regress —
 * <ul>
 *   <li><b>refund happy path:</b> a SUCCEEDED top-up is reversed once via {@link WalletReversalPort}, moves
 *       to REFUNDED, and records the reversal key + reason;</li>
 *   <li><b>refund idempotency:</b> a second refund on an already-REFUNDED row reverses nothing again;</li>
 *   <li><b>refund conflict:</b> refunding a non-SUCCEEDED row is a typed {@code CONFLICT}, no reversal;</li>
 *   <li><b>void:</b> an un-settled (PENDING) row is VOIDED with NO wallet effect;</li>
 *   <li><b>void conflict:</b> voiding a settled/terminal row is a typed {@code CONFLICT};</li>
 *   <li><b>🔒 fence (D18):</b> the ONLY cross-module effect of a refund is a convenience-token reversal of
 *       exactly the purchased amount — no democratic-weight path exists in the collaborator set;</li>
 *   <li><b>audit:</b> a blank reason is rejected (a reversal must explain itself).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    private TopUpRepository topUps;
    @Mock
    private WalletReversalPort walletReversal;

    private RefundService service;

    private final UUID buyerId = UUID.randomUUID();
    private static final MobileMoneyProvider PROVIDER = MobileMoneyProvider.MPESA;

    @BeforeEach
    void setUp() {
        service = new RefundService(topUps, walletReversal);
    }

    /** A settled top-up is reversed once (exact purchased amount) and lands REFUNDED with the reversal key. */
    @Test
    void refund_reversesOnceAndMarksRefunded() {
        TopUp succeeded = succeededTopUp(100);
        UUID id = succeeded.getPublicId();
        when(topUps.findByPublicId(id)).thenReturn(Optional.of(succeeded));
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));

        TopUp result = service.refund(id, "DUPLICATE_CHARGE");

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.REFUNDED);
        assertThat(result.getReversalEventId()).isNotNull();
        assertThat(result.getReversalReason()).isEqualTo("DUPLICATE_CHARGE");
        // 🔒 FENCE: the ONE effect is a convenience-token reversal of exactly the purchased token amount.
        verify(walletReversal).reverseTopUp(eq(WalletOwnerKind.USER), eq(buyerId), eq(100L), anyString());
    }

    /** The reversal uses a stable per-top-up key so a retried refund reverses the wallet exactly once. */
    @Test
    void refund_usesStableReversalKeyPerTopUp() {
        TopUp succeeded = succeededTopUp(50);
        UUID id = succeeded.getPublicId();
        when(topUps.findByPublicId(id)).thenReturn(Optional.of(succeeded));
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));

        service.refund(id, "ADMIN");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(walletReversal, times(1)).reverseTopUp(any(), any(), anyLong(), keyCaptor.capture());
        UUID expected = UUID.nameUUIDFromBytes(("topup-reversal:" + id).getBytes());
        assertThat(keyCaptor.getValue()).isEqualTo(expected.toString());
    }

    /** A refund on an already-REFUNDED row is a no-op (idempotent — no second reversal). */
    @Test
    void refund_isIdempotentOnAlreadyRefunded() {
        TopUp refunded = succeededTopUp(100);
        refunded.markRefunded(UUID.randomUUID(), "FIRST");
        UUID id = refunded.getPublicId();
        when(topUps.findByPublicId(id)).thenReturn(Optional.of(refunded));

        TopUp result = service.refund(id, "SECOND");

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.REFUNDED);
        verifyNoInteractions(walletReversal); // no second reversal
    }

    /** Refunding a non-SUCCEEDED (e.g. PENDING) row is a typed CONFLICT and reverses nothing. */
    @Test
    void refund_rejectsNonSucceeded() {
        TopUp pending = pendingTopUp(100);
        UUID id = pending.getPublicId();
        when(topUps.findByPublicId(id)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.refund(id, "ANY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verifyNoInteractions(walletReversal);
    }

    /** A missing top-up is a 404. */
    @Test
    void refund_unknownTopUpIs404() {
        UUID id = UUID.randomUUID();
        when(topUps.findByPublicId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(id, "ANY"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(walletReversal);
    }

    /** A blank reason is rejected (a reversal must be audited). */
    @Test
    void refund_rejectsBlankReason() {
        assertThatThrownBy(() -> service.refund(UUID.randomUUID(), "  "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verifyNoInteractions(walletReversal);
    }

    /** An un-settled (PENDING) top-up is VOIDED with NO wallet effect. */
    @Test
    void void_marksVoidedWithNoWalletEffect() {
        TopUp pending = pendingTopUp(100);
        UUID id = pending.getPublicId();
        when(topUps.findByPublicId(id)).thenReturn(Optional.of(pending));
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));

        TopUp result = service.voidTopUp(id, "ADMIN_CANCELLED");

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.VOIDED);
        assertThat(result.getReversalReason()).isEqualTo("ADMIN_CANCELLED");
        verifyNoInteractions(walletReversal); // a void never touches the wallet
    }

    /** Voiding a SUCCEEDED (settled) row is a typed CONFLICT — it must be refunded, not voided. */
    @Test
    void void_rejectsSettled() {
        TopUp succeeded = succeededTopUp(100);
        UUID id = succeeded.getPublicId();
        when(topUps.findByPublicId(id)).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> service.voidTopUp(id, "ANY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(topUps, never()).save(any());
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private TopUp succeededTopUp(long tokens) {
        TopUp t = pendingTopUp(tokens);
        t.markSucceeded(UUID.nameUUIDFromBytes(("topup-credit:" + t.getPublicId()).getBytes()));
        return t;
    }

    private TopUp pendingTopUp(long tokens) {
        TopUp t = new TopUp(buyerId, WalletOwnerKind.USER, PROVIDER, tokens * 100, tokens, "TZS",
                "idem-" + UUID.randomUUID());
        setField(t, "publicId", UUID.randomUUID());
        t.markPending("REF-" + UUID.randomUUID());
        return t;
    }

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
