package com.taarifu.payments;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.payments.application.service.TopUpService;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.port.MobileMoneyGateway;
import com.taarifu.payments.domain.repository.TopUpRepository;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TopUpService} — idempotent initiation and degrade-don't-crash on a rail that does
 * not accept the collection (ADR-0015; PRD §23.5, §21 EI-20). No database needed.
 *
 * <p>Responsibility: proves a replayed initiate never triggers a second collection (anti-fraud idempotency)
 * and that a rail rejection marks the top-up FAILED and surfaces a typed {@code SERVICE_UNAVAILABLE} rather
 * than a 500 — the free path always stands.</p>
 */
@ExtendWith(MockitoExtension.class)
class TopUpServiceTest {

    @Mock
    private TopUpRepository topUps;
    @Mock
    private MobileMoneyGateway gateway;

    private TopUpService service;

    private final UUID buyerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Price 10 minor units / token; currency TZS; timeout default — a plain config record (no secrets).
        PaymentsGatewayProperties config = new PaymentsGatewayProperties(
                "logging", null, null, "X-Signature", 10, "TZS", Duration.ofSeconds(8));
        service = new TopUpService(topUps, gateway, config);
    }

    /** A first-time initiate prices the amount, pushes the collection, and lands PENDING. */
    @Test
    void initiate_pushesCollectionAndMarksPending() {
        when(topUps.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.initiateCollection(any()))
                .thenReturn(new MobileMoneyGateway.InitiationResult("REF-1", true));

        TopUp result = service.initiate(buyerId, WalletOwnerKind.USER, MobileMoneyProvider.MPESA,
                25, "+255700000000", "idem-1");

        assertThat(result.getStatus()).isEqualTo(TopUpStatus.PENDING);
        assertThat(result.getProviderRef()).isEqualTo("REF-1");
        assertThat(result.getTokenAmount()).isEqualTo(25);
        assertThat(result.getAmountMinor()).isEqualTo(250); // 25 tokens * 10 minor/token
    }

    /** A replayed initiate (same key) returns the original row and pushes NO second collection. */
    @Test
    void initiate_isIdempotent() {
        TopUp existing = new TopUp(buyerId, WalletOwnerKind.USER, MobileMoneyProvider.MPESA,
                250, 25, "TZS", "idem-dup");
        when(topUps.findByIdempotencyKey("idem-dup")).thenReturn(Optional.of(existing));

        TopUp result = service.initiate(buyerId, WalletOwnerKind.USER, MobileMoneyProvider.MPESA,
                25, "+255700000000", "idem-dup");

        assertThat(result).isSameAs(existing);
        verify(gateway, never()).initiateCollection(any()); // no second push
        verify(topUps, never()).save(any());
    }

    /** A rail that rejects the collection marks the top-up FAILED and surfaces SERVICE_UNAVAILABLE. */
    @Test
    void initiate_railRejected_marksFailedAndDegrades() {
        when(topUps.findByIdempotencyKey("idem-2")).thenReturn(Optional.empty());
        when(topUps.save(any(TopUp.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.initiateCollection(any()))
                .thenReturn(new MobileMoneyGateway.InitiationResult(null, false)); // not accepted

        assertThatThrownBy(() -> service.initiate(buyerId, WalletOwnerKind.USER,
                MobileMoneyProvider.MPESA, 25, "+255700000000", "idem-2"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    /** A non-positive token amount is a bad request (you cannot buy zero/negative tokens). */
    @Test
    void initiate_rejectsNonPositiveTokens() {
        assertThatThrownBy(() -> service.initiate(buyerId, WalletOwnerKind.USER,
                MobileMoneyProvider.MPESA, 0, "+255700000000", "idem-3"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }
}
