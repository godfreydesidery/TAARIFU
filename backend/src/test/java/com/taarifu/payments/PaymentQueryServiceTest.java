package com.taarifu.payments;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.payments.api.dto.AdminPaymentDto;
import com.taarifu.payments.api.dto.PaymentTotalsDto;
import com.taarifu.payments.application.service.PaymentQueryService;
import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import com.taarifu.payments.domain.model.enums.WalletOwnerKind;
import com.taarifu.payments.domain.repository.TopUpRepository;
import com.taarifu.payments.infrastructure.config.PaymentsGatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentQueryService} — the ADMIN read side of payments (ADR-0015 addendum). No
 * database needed; the repository is mocked.
 *
 * <p>Responsibility: proves the service maps entities to the {@link AdminPaymentDto} boundary view (no entity
 * leak, no MSISDN — none is stored), composes window {@link PaymentTotalsDto} from the repository's counts +
 * sums (net settled excludes refunds), and 404s a missing top-up.</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    @Mock
    private TopUpRepository topUps;

    private PaymentQueryService service;

    @BeforeEach
    void setUp() {
        PaymentsGatewayProperties config = new PaymentsGatewayProperties(
                "logging", null, null, "X-Signature", 10, "TZS", Duration.ofSeconds(8));
        service = new PaymentQueryService(topUps, config);
    }

    /** search maps each entity to an AdminPaymentDto (boundary view) and preserves the page metadata. */
    @Test
    void search_mapsToAdminDto() {
        TopUp t = succeededTopUp(100);
        Pageable pageable = PageRequest.of(0, 20);
        Page<TopUp> page = new PageImpl<>(List.of(t), pageable, 1);
        when(topUps.search(eq(TopUpStatus.SUCCEEDED), any(), any(), any(), eq(pageable))).thenReturn(page);

        Page<AdminPaymentDto> result = service.search(TopUpStatus.SUCCEEDED, null, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        AdminPaymentDto dto = result.getContent().get(0);
        assertThat(dto.id()).isEqualTo(t.getPublicId());
        assertThat(dto.status()).isEqualTo(TopUpStatus.SUCCEEDED);
        assertThat(dto.tokenAmount()).isEqualTo(100);
        // The admin DTO is a record of value fields only — no MSISDN field exists to leak (privacy by design).
        assertThat(AdminPaymentDto.class.getRecordComponents())
                .noneMatch(rc -> rc.getName().toLowerCase().contains("msisdn"));
    }

    /** totals composes counts + net settled (SUCCEEDED) and refunded sums from the repository. */
    @Test
    void totals_composesCountsAndNetSettled() {
        when(topUps.countByStatus(eq(TopUpStatus.SUCCEEDED), any(), any(), any())).thenReturn(4L);
        when(topUps.countByStatus(eq(TopUpStatus.FAILED), any(), any(), any())).thenReturn(1L);
        when(topUps.countByStatus(eq(TopUpStatus.PENDING), any(), any(), any())).thenReturn(2L);
        when(topUps.countByStatus(eq(TopUpStatus.REFUNDED), any(), any(), any())).thenReturn(1L);
        when(topUps.sumAmountMinorByStatus(eq(TopUpStatus.SUCCEEDED), any(), any(), any())).thenReturn(40_000L);
        when(topUps.sumAmountMinorByStatus(eq(TopUpStatus.REFUNDED), any(), any(), any())).thenReturn(10_000L);

        PaymentTotalsDto totals = service.totals(null, null, null);

        assertThat(totals.succeededCount()).isEqualTo(4);
        assertThat(totals.failedCount()).isEqualTo(1);
        assertThat(totals.pendingCount()).isEqualTo(2);
        assertThat(totals.refundedCount()).isEqualTo(1);
        assertThat(totals.settledAmountMinor()).isEqualTo(40_000); // net settled excludes refunds
        assertThat(totals.refundedAmountMinor()).isEqualTo(10_000);
        assertThat(totals.currency()).isEqualTo("TZS");
    }

    /** A missing top-up is a 404. */
    @Test
    void get_unknownIs404() {
        UUID id = UUID.randomUUID();
        when(topUps.findByPublicId(id)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private TopUp succeededTopUp(long tokens) {
        TopUp t = new TopUp(UUID.randomUUID(), WalletOwnerKind.USER, MobileMoneyProvider.MPESA,
                tokens * 100, tokens, "TZS", "idem-" + UUID.randomUUID());
        setField(t, "publicId", UUID.randomUUID());
        t.markPending("REF-" + UUID.randomUUID());
        t.markSucceeded(UUID.randomUUID());
        return t;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getSuperclass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
