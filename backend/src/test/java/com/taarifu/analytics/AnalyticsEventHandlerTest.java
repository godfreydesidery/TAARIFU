package com.taarifu.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.analytics.api.AnalyticsApi;
import com.taarifu.analytics.api.RecordEventCommand;
import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.analytics.application.service.AnalyticsEventHandler;
import com.taarifu.analytics.domain.model.enums.AnalyticsChannel;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.model.enums.AnalyticsRole;
import com.taarifu.analytics.domain.model.enums.AnalyticsTier;
import com.taarifu.common.outbox.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsEventHandler} — the consumer half of the civic-activity emission seam
 * (M15; PRD Appendix E; ADR-0013 §2; ADR-0014 §4).
 *
 * <p>Responsibility: proves the handler (a) registers for the single
 * {@link AnalyticsEventTypes#CIVIC_ACTIVITY_RECORDED} taxonomy key; (b) maps each {@link CivicActivityRecorded}
 * payload's string-coded dimensions to the correct {@code AnalyticsEventType}/tier/channel/role enums and
 * records them via {@link AnalyticsApi}; (c) passes the envelope's {@code eventId} through as the idempotency
 * key (so a redelivery is a no-op in the recorder); and (d) <b>drops an unknown event-type string as a no-op</b>
 * (forward compatibility, Appendix E.0 additive) rather than recording or failing.</p>
 */
class AnalyticsEventHandlerTest {

    private final AnalyticsApi analyticsApi = mock(AnalyticsApi.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AnalyticsEventHandler handler = new AnalyticsEventHandler(analyticsApi, objectMapper);

    private static <P> EventEnvelope<P> envelopeOf(UUID eventId, P payload, Instant occurredAt) {
        return new EventEnvelope<>(eventId, AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY, UUID.randomUUID(), payload, occurredAt);
    }

    @Test
    void registersForTheSingleCivicActivityKey() {
        assertThat(handler.handledEventTypes()).containsExactly(AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED);
    }

    @Test
    void records_reportFiled_withGeoAndCategoryDimensions() {
        UUID eventId = UUID.randomUUID();
        UUID ward = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-24T08:00:00Z");
        CivicActivityRecorded fact = CivicActivityRecorded.of(
                AnalyticsEventTypes.REPORT_FILED, ward, category, null, occurredAt);
        when(analyticsApi.record(any())).thenReturn(true);

        handler.handle(envelopeOf(eventId, fact, occurredAt));

        ArgumentCaptor<RecordEventCommand> cmd = ArgumentCaptor.forClass(RecordEventCommand.class);
        verify(analyticsApi).record(cmd.capture());
        RecordEventCommand c = cmd.getValue();
        // Idempotency key is the envelope's eventId (== outbox public_id) — a redelivery dedups on it.
        assertThat(c.eventId()).isEqualTo(eventId);
        assertThat(c.eventType()).isEqualTo(AnalyticsEventType.REPORT_FILED);
        assertThat(c.geoAreaId()).isEqualTo(ward);
        assertThat(c.categoryId()).isEqualTo(category);
        assertThat(c.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void records_petitionSigned_mappingTierChannelRoleStringsToEnums() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-24T09:00:00Z");
        CivicActivityRecorded fact = new CivicActivityRecorded(
                AnalyticsEventTypes.PETITION_SIGNED, occurredAt, null, null, null,
                AnalyticsTier.T3.name(), AnalyticsChannel.USSD.name(), AnalyticsRole.CITIZEN.name(),
                null, null, "REPRESENTATIVE");
        when(analyticsApi.record(any())).thenReturn(true);

        handler.handle(envelopeOf(eventId, fact, occurredAt));

        ArgumentCaptor<RecordEventCommand> cmd = ArgumentCaptor.forClass(RecordEventCommand.class);
        verify(analyticsApi).record(cmd.capture());
        RecordEventCommand c = cmd.getValue();
        assertThat(c.eventType()).isEqualTo(AnalyticsEventType.PETITION_SIGNED);
        assertThat(c.tier()).isEqualTo(AnalyticsTier.T3);
        assertThat(c.channel()).isEqualTo(AnalyticsChannel.USSD);
        assertThat(c.activeRole()).isEqualTo(AnalyticsRole.CITIZEN);
        assertThat(c.outcome()).isEqualTo("REPRESENTATIVE");
        // No PII ever crosses into the command.
        assertThat(c.actorRef()).isNull();
    }

    @Test
    void unknownEventTypeString_isDroppedAsNoOp() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-24T10:00:00Z");
        // A catalogue value this build does not know yet (a newer producer) — must be a forward-compatible no-op.
        CivicActivityRecorded fact = CivicActivityRecorded.of(
                "SOME_FUTURE_EVENT", null, null, "CITIZEN", occurredAt);

        handler.handle(envelopeOf(eventId, fact, occurredAt));

        verify(analyticsApi, never()).record(any());
    }

    @Test
    void unknownTierOrRoleString_isToleratedAsNull() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-06-24T11:00:00Z");
        CivicActivityRecorded fact = new CivicActivityRecorded(
                AnalyticsEventTypes.SURVEY_RESPONDED, occurredAt, null, null, null,
                "T9", "CARRIER_PIGEON", "WIZARD", null, null, "SURVEY");
        when(analyticsApi.record(any())).thenReturn(true);

        handler.handle(envelopeOf(eventId, fact, occurredAt));

        ArgumentCaptor<RecordEventCommand> cmd = ArgumentCaptor.forClass(RecordEventCommand.class);
        verify(analyticsApi).record(cmd.capture());
        RecordEventCommand c = cmd.getValue();
        // The event itself is recorded; the unrecognised tier/channel/role dimensions degrade to null (additive).
        assertThat(c.eventType()).isEqualTo(AnalyticsEventType.SURVEY_RESPONDED);
        assertThat(c.tier()).isNull();
        assertThat(c.channel()).isNull();
        assertThat(c.activeRole()).isNull();
        assertThat(c.outcome()).isEqualTo("SURVEY");
    }
}
