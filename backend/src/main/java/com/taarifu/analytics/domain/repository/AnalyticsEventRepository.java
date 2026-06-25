package com.taarifu.analytics.domain.repository;

import com.taarifu.analytics.domain.model.AnalyticsEvent;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.repository.projection.CountByBucketProjection;
import com.taarifu.analytics.domain.repository.projection.CountByKeyProjection;
import com.taarifu.analytics.domain.repository.projection.LatencyStatsProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence + aggregation port for the append-only {@link AnalyticsEvent} table (M15; PRD Appendix C/E;
 * ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: (a) idempotent INSERT support for the recording path (existence check on
 * {@code event_id}), and (b) the <b>grouped/percentile aggregations</b> the dashboards need, computed
 * entirely in PostgreSQL — counts, channel mix, the verification funnel, SLA-breach counts, engagement
 * counts, moderation actions, and TTFR/TTR/answer-latency distributions. Aggregating in SQL (not the JVM)
 * is what makes these queries scale to national volume (PRD §15) and is the whole point of a separate
 * analytics read model rather than live cross-module reads (ADR-0013; Appendix E pipeline note).</p>
 *
 * <p>Every aggregation takes an inclusive {@code from} / exclusive {@code to} window and optional
 * {@code geoAreaId}/{@code categoryId} filters; a {@code null} filter means "all" (the {@code :x IS NULL OR
 * column = :x} idiom). All native queries here are read-only.</p>
 */
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {

    /**
     * Idempotency guard for the recording path.
     *
     * @param eventId the globally-unique event id.
     * @return {@code true} if a fact with this id has already been recorded.
     */
    boolean existsByEventId(UUID eventId);

    // -----------------------------------------------------------------------------------------------
    // Volume / grouped counts
    // -----------------------------------------------------------------------------------------------

    /**
     * Counts events of one type in the window, optionally filtered by area/category.
     *
     * @param eventType  the event type to count (e.g. {@code REPORT_FILED} for reports volume).
     * @param from       inclusive window start (UTC).
     * @param to         exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all areas).
     * @param categoryId optional category filter ({@code null} = all categories).
     * @return the total count.
     */
    @Query("""
            SELECT COUNT(e) FROM AnalyticsEvent e
            WHERE e.eventType = :eventType
              AND e.occurredAt >= :from AND e.occurredAt < :to
              AND (:geoAreaId IS NULL OR e.geoAreaId = :geoAreaId)
              AND (:categoryId IS NULL OR e.categoryId = :categoryId)
            """)
    long countByType(@Param("eventType") AnalyticsEventType eventType,
                     @Param("from") Instant from,
                     @Param("to") Instant to,
                     @Param("geoAreaId") UUID geoAreaId,
                     @Param("categoryId") UUID categoryId);

    /**
     * Counts events of one type grouped by issue category — the "reports volume by category" dashboard.
     *
     * @param eventType the event type (e.g. {@code REPORT_FILED}).
     * @param from      inclusive window start (UTC).
     * @param to        exclusive window end (UTC).
     * @return (categoryId-as-string, count) rows; the {@code null}-category bucket appears with key {@code null}.
     */
    @Query("""
            SELECT CAST(e.categoryId AS string) AS key, COUNT(e) AS count
            FROM AnalyticsEvent e
            WHERE e.eventType = :eventType
              AND e.occurredAt >= :from AND e.occurredAt < :to
            GROUP BY e.categoryId
            ORDER BY COUNT(e) DESC
            """)
    List<CountByKeyProjection> countByTypeGroupedByCategory(@Param("eventType") AnalyticsEventType eventType,
                                                            @Param("from") Instant from,
                                                            @Param("to") Instant to);

    /**
     * Counts events of one type grouped by geographic area — the "reports volume by area" / geo heatmap feed.
     *
     * @param eventType the event type (e.g. {@code REPORT_FILED}).
     * @param from      inclusive window start (UTC).
     * @param to        exclusive window end (UTC).
     * @return (geoAreaId-as-string, count) rows.
     */
    @Query("""
            SELECT CAST(e.geoAreaId AS string) AS key, COUNT(e) AS count
            FROM AnalyticsEvent e
            WHERE e.eventType = :eventType
              AND e.occurredAt >= :from AND e.occurredAt < :to
            GROUP BY e.geoAreaId
            ORDER BY COUNT(e) DESC
            """)
    List<CountByKeyProjection> countByTypeGroupedByArea(@Param("eventType") AnalyticsEventType eventType,
                                                        @Param("from") Instant from,
                                                        @Param("to") Instant to);

    /**
     * Counts events of one type grouped by {@code channel} — the channel-mix dashboard (% USSD/SMS etc.).
     *
     * @param eventType the event type (e.g. {@code SESSION_STARTED} or {@code REPORT_FILED}).
     * @param from      inclusive window start (UTC).
     * @param to        exclusive window end (UTC).
     * @return (channel-name, count) rows.
     */
    @Query("""
            SELECT CAST(e.channel AS string) AS key, COUNT(e) AS count
            FROM AnalyticsEvent e
            WHERE e.eventType = :eventType
              AND e.occurredAt >= :from AND e.occurredAt < :to
            GROUP BY e.channel
            ORDER BY COUNT(e) DESC
            """)
    List<CountByKeyProjection> countByTypeGroupedByChannel(@Param("eventType") AnalyticsEventType eventType,
                                                           @Param("from") Instant from,
                                                           @Param("to") Instant to);

    /**
     * Counts events of one type grouped by {@code outcome} — used for confirmation/dispute splits,
     * verification-failure reasons, and the moderation-action breakdown.
     *
     * @param eventType the event type (e.g. {@code MODERATION_ACTION_TAKEN}).
     * @param from      inclusive window start (UTC).
     * @param to        exclusive window end (UTC).
     * @return (outcome-code, count) rows.
     */
    @Query("""
            SELECT e.outcome AS key, COUNT(e) AS count
            FROM AnalyticsEvent e
            WHERE e.eventType = :eventType
              AND e.occurredAt >= :from AND e.occurredAt < :to
            GROUP BY e.outcome
            ORDER BY COUNT(e) DESC
            """)
    List<CountByKeyProjection> countByTypeGroupedByOutcome(@Param("eventType") AnalyticsEventType eventType,
                                                           @Param("from") Instant from,
                                                           @Param("to") Instant to);

    /**
     * Counts a set of event types each as one bucket — the engine behind the verification funnel
     * (T0→T3) and any multi-step count read, returned as (eventType-name, count).
     *
     * @param eventTypes the event types to count (each becomes one bucket).
     * @param from       inclusive window start (UTC).
     * @param to         exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @param categoryId optional category filter ({@code null} = all).
     * @return (eventType-name, count) rows for the requested types that have at least one event.
     */
    @Query("""
            SELECT CAST(e.eventType AS string) AS key, COUNT(e) AS count
            FROM AnalyticsEvent e
            WHERE e.eventType IN :eventTypes
              AND e.occurredAt >= :from AND e.occurredAt < :to
              AND (:geoAreaId IS NULL OR e.geoAreaId = :geoAreaId)
              AND (:categoryId IS NULL OR e.categoryId = :categoryId)
            GROUP BY e.eventType
            """)
    List<CountByKeyProjection> countGroupedByType(@Param("eventTypes") List<AnalyticsEventType> eventTypes,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to,
                                                  @Param("geoAreaId") UUID geoAreaId,
                                                  @Param("categoryId") UUID categoryId);

    /**
     * Counts {@code REPORT_ESCALATED} events grouped by {@code breach_type} — the SLA-breach dashboard
     * (TTFR vs TTR breaches), optionally scoped to an area/category for the heatmap cell.
     *
     * @param from       inclusive window start (UTC).
     * @param to         exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @param categoryId optional category filter ({@code null} = all).
     * @return (breachType-name, count) rows.
     */
    @Query("""
            SELECT CAST(e.breachType AS string) AS key, COUNT(e) AS count
            FROM AnalyticsEvent e
            WHERE e.eventType = com.taarifu.analytics.domain.model.enums.AnalyticsEventType.REPORT_ESCALATED
              AND e.breachType IS NOT NULL
              AND e.occurredAt >= :from AND e.occurredAt < :to
              AND (:geoAreaId IS NULL OR e.geoAreaId = :geoAreaId)
              AND (:categoryId IS NULL OR e.categoryId = :categoryId)
            GROUP BY e.breachType
            """)
    List<CountByKeyProjection> countSlaBreachesByType(@Param("from") Instant from,
                                                      @Param("to") Instant to,
                                                      @Param("geoAreaId") UUID geoAreaId,
                                                      @Param("categoryId") UUID categoryId);

    // -----------------------------------------------------------------------------------------------
    // Latency distributions (native — needs PostgreSQL percentile_cont)
    // -----------------------------------------------------------------------------------------------

    /**
     * Computes the latency distribution (count, p50, p90, avg) for one latency-bearing event type — the
     * TTFR ({@code REPORT_FIRST_RESPONDED}), TTR ({@code REPORT_RESOLVED}), or answer-latency
     * ({@code QUESTION_ANSWERED}) dashboards.
     *
     * <p>WHY native SQL: {@code percentile_cont(...) WITHIN GROUP (ORDER BY ...)} is a PostgreSQL ordered-set
     * aggregate with no JPQL equivalent; it is the correct, scalable way to get the median/p90 the §3.3
     * KPIs are stated in. The {@code event_type} is bound as its enum <i>name</i> (the column stores the
     * {@code @Enumerated(STRING)} value).</p>
     *
     * @param eventTypeName the event type name (e.g. {@code "REPORT_RESOLVED"}).
     * @param from          inclusive window start (UTC).
     * @param to            exclusive window end (UTC).
     * @param geoAreaId     optional area filter ({@code null} = all).
     * @param categoryId    optional category filter ({@code null} = all).
     * @return a single-row projection of count + percentiles in seconds.
     */
    @Query(value = """
            SELECT COUNT(latency_seconds)                                                     AS sampleCount,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_seconds)               AS p50Seconds,
                   percentile_cont(0.9) WITHIN GROUP (ORDER BY latency_seconds)               AS p90Seconds,
                   AVG(latency_seconds)                                                        AS avgSeconds
            FROM analytics_event
            WHERE event_type = :eventTypeName
              AND latency_seconds IS NOT NULL
              AND occurred_at >= :from AND occurred_at < :to
              AND (CAST(:geoAreaId AS uuid) IS NULL OR geo_area_id = CAST(:geoAreaId AS uuid))
              AND (CAST(:categoryId AS uuid) IS NULL OR category_id = CAST(:categoryId AS uuid))
            """, nativeQuery = true)
    LatencyStatsProjection latencyStats(@Param("eventTypeName") String eventTypeName,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        @Param("geoAreaId") UUID geoAreaId,
                                        @Param("categoryId") UUID categoryId);

    // -----------------------------------------------------------------------------------------------
    // Time-bucketed trends (native — needs PostgreSQL date_trunc)
    // -----------------------------------------------------------------------------------------------

    /**
     * Counts events of one type per time bucket — the "reports volume over time" trend (PRD §3.3;
     * Appendix C), optionally scoped to an area/category.
     *
     * <p>WHY native SQL: {@code date_trunc} is a PostgreSQL function with no JPQL equivalent. The bucket
     * field ({@code 'day'}/{@code 'week'}/{@code 'month'}) is passed as a <b>bind parameter</b> to
     * {@code date_trunc(text, timestamptz)} — never spliced into the SQL string — so there is no injection
     * seam; the caller restricts it further to the {@link com.taarifu.analytics.api.dto.TimeBucket} enum's
     * fixed literal (defence in depth, ADR-0020 §1). Returns one row per non-empty bucket, chronologically
     * ordered so the client renders the series without re-sorting.</p>
     *
     * @param eventTypeName the event type name bound as text (e.g. {@code "REPORT_FILED"}); the column
     *                      stores the {@code @Enumerated(STRING)} value.
     * @param truncField    the {@code date_trunc} field literal ({@code "day"}/{@code "week"}/{@code "month"})
     *                      — supplied from the {@code TimeBucket} enum, never raw caller text.
     * @param from          inclusive window start (UTC).
     * @param to            exclusive window end (UTC).
     * @param geoAreaId     optional area filter ({@code null} = all).
     * @param categoryId    optional category filter ({@code null} = all).
     * @return (bucketStart, count) rows, oldest → newest.
     */
    @Query(value = """
            SELECT date_trunc(:truncField, occurred_at) AS bucketStart,
                   COUNT(*)                              AS count
            FROM analytics_event
            WHERE event_type = :eventTypeName
              AND occurred_at >= :from AND occurred_at < :to
              AND (CAST(:geoAreaId AS uuid) IS NULL OR geo_area_id = CAST(:geoAreaId AS uuid))
              AND (CAST(:categoryId AS uuid) IS NULL OR category_id = CAST(:categoryId AS uuid))
            GROUP BY date_trunc(:truncField, occurred_at)
            ORDER BY date_trunc(:truncField, occurred_at) ASC
            """, nativeQuery = true)
    List<CountByBucketProjection> countByTypeBucketed(@Param("eventTypeName") String eventTypeName,
                                                      @Param("truncField") String truncField,
                                                      @Param("from") Instant from,
                                                      @Param("to") Instant to,
                                                      @Param("geoAreaId") UUID geoAreaId,
                                                      @Param("categoryId") UUID categoryId);

    /**
     * Counts {@code REPORT_ESCALATED} events per time bucket — the <b>SLA-breach trend</b> (PRD §3.3;
     * Appendix C), optionally scoped to an area/category. Only rows that carry a {@code breach_type} (an
     * actual SLA breach, not a manual escalation without a breach clock) are counted.
     *
     * <p>WHY native + bound bucket field: identical rationale to {@link #countByTypeBucketed}.</p>
     *
     * @param truncField the {@code date_trunc} field literal from the {@code TimeBucket} enum (never raw text).
     * @param from       inclusive window start (UTC).
     * @param to         exclusive window end (UTC).
     * @param geoAreaId  optional area filter ({@code null} = all).
     * @param categoryId optional category filter ({@code null} = all).
     * @return (bucketStart, count) rows, oldest → newest.
     */
    @Query(value = """
            SELECT date_trunc(:truncField, occurred_at) AS bucketStart,
                   COUNT(*)                              AS count
            FROM analytics_event
            WHERE event_type = 'REPORT_ESCALATED'
              AND breach_type IS NOT NULL
              AND occurred_at >= :from AND occurred_at < :to
              AND (CAST(:geoAreaId AS uuid) IS NULL OR geo_area_id = CAST(:geoAreaId AS uuid))
              AND (CAST(:categoryId AS uuid) IS NULL OR category_id = CAST(:categoryId AS uuid))
            GROUP BY date_trunc(:truncField, occurred_at)
            ORDER BY date_trunc(:truncField, occurred_at) ASC
            """, nativeQuery = true)
    List<CountByBucketProjection> countSlaBreachesBucketed(@Param("truncField") String truncField,
                                                           @Param("from") Instant from,
                                                           @Param("to") Instant to,
                                                           @Param("geoAreaId") UUID geoAreaId,
                                                           @Param("categoryId") UUID categoryId);
}
