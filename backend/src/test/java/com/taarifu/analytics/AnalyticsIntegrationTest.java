package com.taarifu.analytics;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.analytics.api.RecordEventCommand;
import com.taarifu.analytics.api.dto.BreakdownDto;
import com.taarifu.analytics.api.dto.FunnelDto;
import com.taarifu.analytics.api.dto.LatencyStatsDto;
import com.taarifu.analytics.api.dto.MetricPointDto;
import com.taarifu.analytics.api.dto.TimeBucket;
import com.taarifu.analytics.api.dto.TimeSeriesDto;
import com.taarifu.analytics.api.dto.VolumeReportDto;
import com.taarifu.analytics.application.service.AnalyticsQueryService;
import com.taarifu.analytics.application.service.AnalyticsRecordingService;
import com.taarifu.analytics.domain.model.enums.AnalyticsChannel;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.model.enums.AnalyticsRole;
import com.taarifu.analytics.domain.model.enums.AnalyticsTier;
import com.taarifu.analytics.domain.model.enums.BreachType;
import com.taarifu.analytics.domain.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration tests for the analytics module (M15; CLAUDE.md §10; ADR-0009).
 *
 * <p>Responsibility: proves against a <b>real PostgreSQL</b> that (a) the V91 migration matches the
 * {@code AnalyticsEvent} entity so {@code ddl-auto=validate} passes (the context simply starting is that
 * proof), (b) the recorder appends and is <b>idempotent</b> on {@code event_id} (the globally-unique index
 * fires), and (c) the dashboard aggregations — reports volume by category, TTR percentiles via PostgreSQL
 * {@code percentile_cont}, SLA-breach counts, the verification funnel, channel mix, and moderation actions —
 * return correct numbers. These guarantees live in Postgres indexes/functions, not Java, so they need a real
 * DB (ADR-0009).</p>
 *
 * <p>Docker is required; in environments without it this test is skipped by CI infra, while the module's
 * unit tests (funnel arithmetic, recording idempotency) still prove the logic on every lane.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AnalyticsIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private AnalyticsRecordingService recordingService;
    @Autowired
    private AnalyticsQueryService queryService;
    @Autowired
    private AnalyticsEventRepository events;

    private final Instant base = Instant.parse("2026-06-10T09:00:00Z");
    private final Instant from = Instant.parse("2026-06-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-07-01T00:00:00Z");

    @BeforeEach
    void clear() {
        events.deleteAll();
    }

    /** Records a fact via the public API at the fixed {@link #base} instant. */
    private boolean record(AnalyticsEventType type, UUID geo, UUID cat, AnalyticsTier tier,
                           AnalyticsChannel channel, Long latency, BreachType breach, String outcome) {
        return recordAt(base, type, geo, cat, tier, channel, latency, breach, outcome);
    }

    /** Records a fact via the public API at an explicit {@code occurredAt} (used by the trend tests). */
    private boolean recordAt(Instant occurredAt, AnalyticsEventType type, UUID geo, UUID cat,
                             AnalyticsTier tier, AnalyticsChannel channel, Long latency, BreachType breach,
                             String outcome) {
        return recordingService.record(new RecordEventCommand(
                UUID.randomUUID(), type, occurredAt, "ref-" + UUID.randomUUID(), geo, cat, tier, channel,
                AnalyticsRole.CITIZEN, latency, breach, outcome));
    }

    @Test
    void record_isIdempotentOnEventId() {
        UUID eventId = UUID.randomUUID();
        RecordEventCommand cmd = new RecordEventCommand(eventId, AnalyticsEventType.REPORT_FILED, base,
                "ref-x", UUID.randomUUID(), UUID.randomUUID(), AnalyticsTier.T2, AnalyticsChannel.APP,
                AnalyticsRole.CITIZEN, null, null, null);

        assertThat(recordingService.record(cmd)).isTrue();
        // Same event_id replayed → no-op, no second row (the unique index guarantees it).
        assertThat(recordingService.record(cmd)).isFalse();
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void reportsVolume_totalAndByCategory() {
        UUID catWater = UUID.randomUUID();
        UUID catRoads = UUID.randomUUID();
        UUID ward = UUID.randomUUID();
        record(AnalyticsEventType.REPORT_FILED, ward, catWater, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);
        record(AnalyticsEventType.REPORT_FILED, ward, catWater, AnalyticsTier.T2, AnalyticsChannel.USSD, null, null, null);
        record(AnalyticsEventType.REPORT_FILED, ward, catRoads, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);

        VolumeReportDto dto = queryService.reportsVolume(from, to, null, null);
        assertThat(dto.total()).isEqualTo(3);
        assertThat(dto.byCategory()).hasSize(2);
        // Water has 2 (the descending-ordered top bucket).
        assertThat(dto.byCategory().get(0).key()).isEqualTo(catWater.toString());
        assertThat(dto.byCategory().get(0).count()).isEqualTo(2);

        // Category filter narrows the headline total.
        assertThat(queryService.reportsVolume(from, to, null, catRoads).total()).isEqualTo(1);
    }

    @Test
    void ttr_percentilesViaPercentileCont() {
        UUID cat = UUID.randomUUID();
        // TTR samples: 100, 200, 300, 400, 900 seconds.
        for (long s : new long[]{100, 200, 300, 400, 900}) {
            record(AnalyticsEventType.REPORT_RESOLVED, null, cat, AnalyticsTier.T2, AnalyticsChannel.APP, s, null, null);
        }
        LatencyStatsDto stats = queryService.latency(
                AnalyticsEventType.REPORT_RESOLVED, "TTR", from, to, null, null);

        assertThat(stats.sampleCount()).isEqualTo(5);
        // percentile_cont(0.5) over {100,200,300,400,900} = 300.
        assertThat(stats.p50Seconds()).isEqualTo(300.0);
        // percentile_cont(0.9) interpolates between the 4th and 5th = 400 + 0.6*(900-400) = 700.
        assertThat(stats.p90Seconds()).isEqualTo(700.0);
    }

    @Test
    void slaBreaches_splitByBreachType() {
        UUID cat = UUID.randomUUID();
        record(AnalyticsEventType.REPORT_ESCALATED, null, cat, null, null, null, BreachType.TTFR, "SLA_BREACH");
        record(AnalyticsEventType.REPORT_ESCALATED, null, cat, null, null, null, BreachType.TTFR, "SLA_BREACH");
        record(AnalyticsEventType.REPORT_ESCALATED, null, cat, null, null, null, BreachType.TTR, "SLA_BREACH");

        BreakdownDto dto = queryService.slaBreaches(from, to, null, cat);
        assertThat(dto.total()).isEqualTo(3);
        assertThat(dto.points()).extracting(MetricPointDto::key).contains("TTFR", "TTR");
        assertThat(dto.points().stream().filter(p -> "TTFR".equals(p.key())).findFirst().orElseThrow().count())
                .isEqualTo(2);
    }

    @Test
    void verificationFunnel_countsEachStep() {
        UUID ward = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            record(AnalyticsEventType.ACCOUNT_SIGNED_UP, ward, null, AnalyticsTier.T1, AnalyticsChannel.APP, null, null, null);
        }
        for (int i = 0; i < 3; i++) {
            record(AnalyticsEventType.PROFILE_COMPLETED, ward, null, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);
        }
        record(AnalyticsEventType.IDENTITY_VERIFIED, ward, null, AnalyticsTier.T3, AnalyticsChannel.APP,
                3600L, null, null);

        FunnelDto funnel = queryService.verificationFunnel(from, to, null);
        assertThat(funnel.steps().get(0).count()).isEqualTo(4); // signups
        assertThat(funnel.steps().get(1).count()).isEqualTo(3); // profile
        assertThat(funnel.steps().get(3).count()).isEqualTo(1); // verified
        assertThat(funnel.steps().get(3).conversionFromTop()).isEqualTo(0.25);
    }

    @Test
    void channelMix_distributesReportsAcrossChannels() {
        UUID ward = UUID.randomUUID();
        record(AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.USSD, null, null, null);
        record(AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.USSD, null, null, null);
        record(AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);

        BreakdownDto dto = queryService.channelMix(AnalyticsEventType.REPORT_FILED, from, to);
        assertThat(dto.total()).isEqualTo(3);
        assertThat(dto.points().get(0).key()).isEqualTo("USSD"); // top channel
        assertThat(dto.points().get(0).count()).isEqualTo(2);
    }

    @Test
    void moderationActions_splitByOutcome() {
        record(AnalyticsEventType.MODERATION_ACTION_TAKEN, null, null, null, AnalyticsChannel.ADMIN, null, null, "HIDE");
        record(AnalyticsEventType.MODERATION_ACTION_TAKEN, null, null, null, AnalyticsChannel.ADMIN, null, null, "REMOVE");
        record(AnalyticsEventType.MODERATION_ACTION_TAKEN, null, null, null, AnalyticsChannel.ADMIN, null, null, "REMOVE");

        BreakdownDto dto = queryService.moderationActions(from, to);
        assertThat(dto.total()).isEqualTo(3);
        assertThat(dto.points().get(0).key()).isEqualTo("REMOVE");
        assertThat(dto.points().get(0).count()).isEqualTo(2);
    }

    @Test
    void autoModerationTriaged_recordsAndIsQueryableByTypeAndSignal() {
        // Mirrors exactly what moderation.AutoAssistService emits (ADR-0018 §3): event type
        // AUTO_MODERATION_TRIAGED, activeRole MODERATOR, the top ContentSignal as the `outcome` code,
        // and the held flag carried in the breach field (HELD/NOT_HELD are not BreachType members, so the
        // handler maps them to null — here we record null breachType directly, the post-parse value).
        // BEFORE V171 the catalogue lacked the value and the fact was dropped as a no-op; this proves it now
        // persists and is queryable for the auto-vs-manual split KPI (US-12.3).
        boolean recordedHeldProfanity = recordingService.record(new RecordEventCommand(
                UUID.randomUUID(), AnalyticsEventType.AUTO_MODERATION_TRIAGED, base, null, null, null,
                null, null, AnalyticsRole.MODERATOR, null, null, "PROFANITY"));
        boolean recordedHeldPii = recordingService.record(new RecordEventCommand(
                UUID.randomUUID(), AnalyticsEventType.AUTO_MODERATION_TRIAGED, base, null, null, null,
                null, null, AnalyticsRole.MODERATOR, null, null, "PII"));
        boolean recordedNotHeld = recordingService.record(new RecordEventCommand(
                UUID.randomUUID(), AnalyticsEventType.AUTO_MODERATION_TRIAGED, base, null, null, null,
                null, null, AnalyticsRole.MODERATOR, null, null, "PROFANITY"));

        assertThat(recordedHeldProfanity).isTrue();
        assertThat(recordedHeldPii).isTrue();
        assertThat(recordedNotHeld).isTrue();

        // Queryable by the new type (the headline auto-triage volume).
        assertThat(events.countByType(AnalyticsEventType.AUTO_MODERATION_TRIAGED, from, to, null, null))
                .isEqualTo(3);

        // Queryable grouped by the top safety signal (the `outcome` dimension) — PROFANITY (2) tops PII (1).
        var bySignal = events.countByTypeGroupedByOutcome(
                AnalyticsEventType.AUTO_MODERATION_TRIAGED, from, to);
        assertThat(bySignal).hasSize(2);
        assertThat(bySignal.get(0).getKey()).isEqualTo("PROFANITY");
        assertThat(bySignal.get(0).getCount()).isEqualTo(2);
    }

    @Test
    void reportsTrend_bucketsByDayViaDateTrunc() {
        UUID ward = UUID.randomUUID();
        Instant day1 = Instant.parse("2026-06-10T08:00:00Z");
        Instant day1Later = Instant.parse("2026-06-10T20:00:00Z"); // same day, different hour
        Instant day3 = Instant.parse("2026-06-12T09:00:00Z");
        recordAt(day1, AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);
        recordAt(day1Later, AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.USSD, null, null, null);
        recordAt(day3, AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);

        TimeSeriesDto series = queryService.reportsTrend(TimeBucket.DAY, from, to, null, null);

        // Two non-empty day buckets; the two same-day events collapse to one bucket (date_trunc to midnight).
        assertThat(series.bucket()).isEqualTo(TimeBucket.DAY);
        assertThat(series.points()).hasSize(2);
        // Chronological order (oldest → newest) and correct per-bucket counts.
        assertThat(series.points().get(0).bucketStart()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(series.points().get(0).count()).isEqualTo(2);
        assertThat(series.points().get(1).bucketStart()).isEqualTo(Instant.parse("2026-06-12T00:00:00Z"));
        assertThat(series.points().get(1).count()).isEqualTo(1);
    }

    @Test
    void slaBreachTrend_countsOnlyEscalationsWithBreachType() {
        UUID ward = UUID.randomUUID();
        Instant day1 = Instant.parse("2026-06-10T08:00:00Z");
        Instant day2 = Instant.parse("2026-06-11T08:00:00Z");
        // Two real SLA breaches on day1, one on day2.
        recordAt(day1, AnalyticsEventType.REPORT_ESCALATED, ward, null, null, null, null, BreachType.TTFR, "SLA_BREACH");
        recordAt(day1, AnalyticsEventType.REPORT_ESCALATED, ward, null, null, null, null, BreachType.TTR, "SLA_BREACH");
        recordAt(day2, AnalyticsEventType.REPORT_ESCALATED, ward, null, null, null, null, BreachType.TTFR, "SLA_BREACH");
        // A manual escalation WITHOUT a breach clock must NOT be counted in the SLA-breach trend.
        recordAt(day2, AnalyticsEventType.REPORT_ESCALATED, ward, null, null, null, null, null, "MANUAL");
        // A filed report is irrelevant to this trend.
        recordAt(day1, AnalyticsEventType.REPORT_FILED, ward, null, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);

        TimeSeriesDto series = queryService.slaBreachTrend(TimeBucket.DAY, from, to, null, null);

        assertThat(series.metric()).isEqualTo("SLA_BREACH");
        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(0).count()).isEqualTo(2); // day1 breaches
        assertThat(series.points().get(1).count()).isEqualTo(1); // day2 breach (manual escalation excluded)
    }

    @Test
    void overview_composesHeadlineKpisOverOneWindow() {
        UUID ward = UUID.randomUUID();
        UUID cat = UUID.randomUUID();
        record(AnalyticsEventType.REPORT_FILED, ward, cat, AnalyticsTier.T2, AnalyticsChannel.USSD, null, null, null);
        record(AnalyticsEventType.REPORT_FILED, ward, cat, AnalyticsTier.T2, AnalyticsChannel.APP, null, null, null);
        record(AnalyticsEventType.REPORT_RESOLVED, ward, cat, AnalyticsTier.T2, AnalyticsChannel.APP, 300L, null, null);
        record(AnalyticsEventType.ACCOUNT_SIGNED_UP, ward, null, AnalyticsTier.T1, AnalyticsChannel.APP, null, null, null);

        var overview = queryService.overview(from, to, null);

        assertThat(overview.from()).isEqualTo(from);
        assertThat(overview.to()).isEqualTo(to);
        assertThat(overview.reportsVolumeTotal()).isEqualTo(2);
        assertThat(overview.ttr().sampleCount()).isEqualTo(1);
        assertThat(overview.ttr().p50Seconds()).isEqualTo(300.0);
        assertThat(overview.channelMix().total()).isEqualTo(2); // two filed reports across channels
        assertThat(overview.verificationFunnel().steps().get(0).count()).isEqualTo(1); // one signup
    }

    @Test
    void occurredAt_windowExcludesOutOfRangeFacts() {
        UUID cat = UUID.randomUUID();
        // One inside the window, one a year later (outside).
        recordingService.record(new RecordEventCommand(UUID.randomUUID(), AnalyticsEventType.REPORT_FILED,
                base, "r1", null, cat, AnalyticsTier.T2, AnalyticsChannel.APP, AnalyticsRole.CITIZEN, null, null, null));
        recordingService.record(new RecordEventCommand(UUID.randomUUID(), AnalyticsEventType.REPORT_FILED,
                base.plus(365, ChronoUnit.DAYS), "r2", null, cat, AnalyticsTier.T2, AnalyticsChannel.APP,
                AnalyticsRole.CITIZEN, null, null, null));

        assertThat(queryService.reportsVolume(from, to, null, null).total()).isEqualTo(1);
    }
}
