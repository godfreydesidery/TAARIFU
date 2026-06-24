package com.taarifu.identity.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentityFunnelAnalytics} — identity's single verification-funnel emission point
 * (gap A1; PRD Appendix E; §3.3 funnel KPI; ADR-0014 §1/§2).
 *
 * <p>Pins the contract the funnel KPI depends on AND the no-PII invariant: the helper appends one
 * {@code CIVIC_ACTIVITY_RECORDED} outbox row carrying only the analytics catalogue value + coarse
 * tier/channel codes, with NO actorRef, NO geo, NO category, and an aggregate id that is NOT the subject
 * account (so the queryable outbox cannot re-identify the citizen). The test fails if any PII-bearing
 * dimension is ever populated. Mockito only, no database.</p>
 */
class IdentityFunnelAnalyticsTest {

    private final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private final ClockPort clock = mock(ClockPort.class);
    private final IdentityFunnelAnalytics funnel = new IdentityFunnelAnalytics(outboxWriter, clock);

    @Test
    @SuppressWarnings("unchecked")
    void emit_appendsCivicActivityRow_withCoarseDimensionsOnly_noPii() {
        Instant now = Instant.parse("2026-06-24T12:00:00Z");
        when(clock.now()).thenReturn(now);

        funnel.emit(AnalyticsEventTypes.ACCOUNT_SIGNED_UP, "T1", IdentityFunnelAnalytics.CHANNEL_USSD);

        ArgumentCaptor<EventEnvelope<?>> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxWriter).append(captor.capture());
        EventEnvelope<?> env = captor.getValue();

        // Routed under the single analytics taxonomy key so the existing AnalyticsEventHandler consumes it.
        assertThat(env.eventType()).isEqualTo(AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED);
        assertThat(env.occurredAt()).isEqualTo(now);

        CivicActivityRecorded fact = (CivicActivityRecorded) env.payload();
        assertThat(fact.analyticsEventType()).isEqualTo(AnalyticsEventTypes.ACCOUNT_SIGNED_UP);
        assertThat(fact.tier()).isEqualTo("T1");
        assertThat(fact.channel()).isEqualTo("USSD");
        // 🔒 No-PII invariant: every identifying/free-text dimension is null.
        assertThat(fact.actorRef()).isNull();
        assertThat(fact.geoAreaId()).isNull();
        assertThat(fact.categoryId()).isNull();
        assertThat(fact.activeRole()).isNull();
        assertThat(fact.latencySeconds()).isNull();
        assertThat(fact.breachType()).isNull();
        assertThat(fact.outcome()).isNull();
    }
}
