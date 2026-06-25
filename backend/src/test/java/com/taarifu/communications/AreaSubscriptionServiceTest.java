package com.taarifu.communications;

import com.taarifu.communications.application.service.AreaSubscriptionService;
import com.taarifu.communications.application.service.SubscriptionService;
import com.taarifu.communications.domain.model.Subscription;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AreaSubscriptionService} — the published {@code AreaSubscriptionApi} impl (A3,
 * ADR-0019).
 *
 * <p>Asserts the area-follow façade delegates to the canonical idempotent {@code SubscriptionService.follow}
 * with {@code AREA} target type and the supplied profile/ward ids, and returns the edge's public id — so the
 * cross-module path and the in-app follow path can never diverge (DRY).</p>
 */
class AreaSubscriptionServiceTest {

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final AreaSubscriptionService service = new AreaSubscriptionService(subscriptionService);

    /** subscribeArea delegates to follow(AREA) and returns the resulting edge's public id. */
    @Test
    void subscribeArea_delegatesToFollow_asArea() {
        UUID subscriber = UUID.randomUUID();
        UUID ward = UUID.randomUUID();
        Subscription edge = Subscription.follow(subscriber, SubscriptionTargetType.AREA, ward);
        when(subscriptionService.follow(eq(subscriber), eq(SubscriptionTargetType.AREA.name()), eq(ward)))
                .thenReturn(edge);

        UUID result = service.subscribeArea(subscriber, ward);

        verify(subscriptionService).follow(subscriber, SubscriptionTargetType.AREA.name(), ward);
        assertThat(result).isEqualTo(edge.getPublicId());
    }
}
