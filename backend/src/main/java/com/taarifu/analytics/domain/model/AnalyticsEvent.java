package com.taarifu.analytics.domain.model;

import com.taarifu.analytics.domain.model.enums.AnalyticsChannel;
import com.taarifu.analytics.domain.model.enums.AnalyticsEventType;
import com.taarifu.analytics.domain.model.enums.AnalyticsRole;
import com.taarifu.analytics.domain.model.enums.AnalyticsTier;
import com.taarifu.analytics.domain.model.enums.BreachType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable, append-only product-analytics fact — the unit the dashboards aggregate over
 * (PRD §3.3 KPIs, Appendix C dashboards, Appendix E measurement plan; M15).
 *
 * <p>Responsibility: records that a measurable thing happened ({@link #eventType} at {@link #occurredAt})
 * under a small set of <b>non-identifying dimensions</b> ({@link #geoAreaId}, {@link #categoryId},
 * {@link #tier}, {@link #channel}, {@link #activeRole}) plus the few <b>measures</b> the headline KPIs
 * need ({@link #latencySeconds} for TTFR/TTR/answer-latency, {@link #breachType} for the SLA heatmap,
 * {@link #outcome} for funnel/dispute outcomes). The aggregation endpoints group/count over this single
 * table — they do <b>not</b> reach live into sibling modules (ADR-0013; Appendix E pipeline is a separate
 * read model).</p>
 *
 * <p><b>🔒 No PII, ever (Appendix E.4, binding — §18/PDPA):</b> this row carries <b>no</b> names, phone,
 * email, national/voter ID, free-text bodies, photos, or precise GPS. Identity is a single pseudonymous
 * {@link #actorRef} — a salted hash supplied by the caller, never the account UUID, and nullable for
 * guest/pre-auth events. Geography is truncated to <b>ward-or-coarser</b> public area ids ({@link #geoAreaId});
 * village/hamlet/Mtaa and raw GPS are never recorded here. All {@code *Id} dimensions are opaque public
 * {@code UUID}s referenced by id only (cross-module, no FK — ADR-0013).</p>
 *
 * <p>WHY this is <b>not</b> a {@link com.taarifu.common.domain.model.BaseEntity} (mirrors {@code
 * TokenTransaction} and {@code AuditEvent}): an analytics fact is never updated or soft-deleted — it has
 * no {@code version}, no {@code updated_*}, no {@code deleted} columns. A correction is a new fact, never an
 * edit; columns are {@code updatable = false}. The application is granted only {@code INSERT}+{@code SELECT}
 * on this table (the migration documents this). PDPA erasure (Appendix E.4) deletes the actor-ref salt
 * mapping (held in identity), orphaning these rows — it never mutates them.</p>
 *
 * <p>WHY {@link #eventId} is globally unique (idempotency, Appendix E.0/E.3): events derive from the same
 * transactional outbox that drives feed/notification fan-out, so a replayed or duplicated emission carries
 * the same {@code eventId} and hits the unique constraint — recorded exactly once, no double-count. The
 * recorder swallows the duplicate as a no-op (see {@code AnalyticsRecordingService}).</p>
 */
@Entity
@Table(name = "analytics_event",
        uniqueConstraints = @UniqueConstraint(name = "uq_analytics_event_event_id", columnNames = "event_id"),
        indexes = {
                // Time-bucketed counts per event type — the backbone of every dashboard query.
                @Index(name = "ix_analytics_event_type_time", columnList = "event_type, occurred_at"),
                // Geo + category + time — reports volume / SLA heatmap by area×category×time.
                @Index(name = "ix_analytics_event_geo_cat", columnList = "geo_area_id, category_id, occurred_at"),
                // Funnel and channel-mix segmentation.
                @Index(name = "ix_analytics_event_tier", columnList = "tier, occurred_at"),
                @Index(name = "ix_analytics_event_channel", columnList = "channel, occurred_at")
        })
public class AnalyticsEvent {

    /** Internal surrogate PK (append-only; never exposed in any DTO). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /**
     * Globally-unique idempotency key for this fact (Appendix E.0 {@code event_id}). One business
     * occurrence carries one key; a replayed outbox emission is a no-op (no double-count).
     */
    @Column(name = "event_id", updatable = false, nullable = false, unique = true)
    private UUID eventId;

    /** The kind of measurable occurrence (drives which dashboard counts it). */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", updatable = false, nullable = false, length = 48)
    private AnalyticsEventType eventType;

    /**
     * When the thing actually happened (server UTC). The pipeline keys on <b>occurred-at, not ingest
     * time</b>, so late/offline-synced events do not skew funnels (Appendix E.3).
     */
    @Column(name = "occurred_at", updatable = false, nullable = false)
    private Instant occurredAt;

    /**
     * Pseudonymous actor reference — a <b>salted hash</b> of the account, supplied by the caller
     * (Appendix E.0 {@code actor_ref}). NEVER the account UUID, name, phone, or ID. {@code null} for
     * guest/pre-auth events. Used only to de-duplicate distinct actors within an aggregate (e.g. unique
     * filers), never to resolve a person.
     */
    @Column(name = "actor_ref", updatable = false, length = 128)
    private String actorRef;

    /**
     * Public id of the geographic area, truncated to <b>ward or coarser</b> (region/council/ward), or
     * {@code null}. Cross-module reference by id only (no FK — ADR-0013). Pinned-below-ward is never sent.
     */
    @Column(name = "geo_area_id", updatable = false)
    private UUID geoAreaId;

    /** Public id of the issue category this event concerns, or {@code null}. Cross-module id only. */
    @Column(name = "category_id", updatable = false)
    private UUID categoryId;

    /** The actor's trust tier at the time (verification-funnel dimension), or {@code null}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", updatable = false, length = 8)
    private AnalyticsTier tier;

    /** The channel the event originated through (channel-mix dimension), or {@code null}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", updatable = false, length = 16)
    private AnalyticsChannel channel;

    /** The active role/"hat" the actor held (role-segmentation dimension), or {@code null}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "active_role", updatable = false, length = 16)
    private AnalyticsRole activeRole;

    /**
     * The headline latency measure in <b>seconds</b> for events that carry one — TTFR on
     * {@code REPORT_FIRST_RESPONDED}, TTR on {@code REPORT_RESOLVED}, answer latency on
     * {@code QUESTION_ANSWERED}, verify latency on {@code IDENTITY_VERIFIED}. {@code null} otherwise.
     * Stored as a long so percentile/median aggregations run in SQL without precision loss.
     */
    @Column(name = "latency_seconds", updatable = false)
    private Long latencySeconds;

    /** Which SLA clock breached on a {@code REPORT_ESCALATED} event (SLA heatmap), or {@code null}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "breach_type", updatable = false, length = 8)
    private BreachType breachType;

    /**
     * A short outcome/qualifier code for events whose dashboards split by outcome — e.g.
     * {@code CONFIRMED}/{@code DISPUTED} on confirmation, {@code DUPLICATE_ID}/{@code NO_MATCH} on a
     * verification failure, {@code HIDE}/{@code REMOVE}/{@code WARN} on a moderation action. Drawn from a
     * controlled vocabulary by the caller; never free-text/PII. {@code null} when not applicable.
     */
    @Column(name = "outcome", updatable = false, length = 32)
    private String outcome;

    /** JPA requires a no-arg constructor; not for application use. Use {@link Builder}. */
    protected AnalyticsEvent() {
    }

    /** Defaults the idempotency key + occurred-at before insert if absent (mirrors the ledger contract). */
    @PrePersist
    void onPersist() {
        if (this.eventId == null) {
            this.eventId = UUID.randomUUID();
        }
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    /** @return internal numeric PK (backend-only). */
    public Long getId() {
        return id;
    }

    /** @return the globally-unique idempotency key. */
    public UUID getEventId() {
        return eventId;
    }

    /** @return the event type. */
    public AnalyticsEventType getEventType() {
        return eventType;
    }

    /** @return when the occurrence actually happened (UTC). */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** @return the pseudonymous actor reference, or {@code null}. */
    public String getActorRef() {
        return actorRef;
    }

    /** @return the geographic-area public id (ward-or-coarser), or {@code null}. */
    public UUID getGeoAreaId() {
        return geoAreaId;
    }

    /** @return the issue-category public id, or {@code null}. */
    public UUID getCategoryId() {
        return categoryId;
    }

    /** @return the actor's trust tier, or {@code null}. */
    public AnalyticsTier getTier() {
        return tier;
    }

    /** @return the origin channel, or {@code null}. */
    public AnalyticsChannel getChannel() {
        return channel;
    }

    /** @return the active role/"hat", or {@code null}. */
    public AnalyticsRole getActiveRole() {
        return activeRole;
    }

    /** @return the latency measure in seconds, or {@code null}. */
    public Long getLatencySeconds() {
        return latencySeconds;
    }

    /** @return the breached SLA clock, or {@code null}. */
    public BreachType getBreachType() {
        return breachType;
    }

    /** @return the outcome/qualifier code, or {@code null}. */
    public String getOutcome() {
        return outcome;
    }

    /**
     * Fluent builder for an immutable analytics fact.
     *
     * <p>WHY a builder (not a long constructor): a fact has many optional dimensions/measures; a builder
     * keeps each call site readable and the entity's fields write-once, so the row stays immutable once
     * persisted (the same shape as {@code TokenTransaction.Builder}).</p>
     */
    public static final class Builder {
        private final AnalyticsEvent event = new AnalyticsEvent();

        /**
         * Starts a fact for a type at an instant.
         *
         * @param eventType  the kind of occurrence.
         * @param occurredAt when it actually happened (UTC).
         * @return a new builder.
         */
        public static Builder of(AnalyticsEventType eventType, Instant occurredAt) {
            Builder b = new Builder();
            b.event.eventType = eventType;
            b.event.occurredAt = occurredAt;
            return b;
        }

        /** @param eventId the globally-unique idempotency key. @return this builder. */
        public Builder eventId(UUID eventId) {
            event.eventId = eventId;
            return this;
        }

        /** @param actorRef pseudonymous salted-hash actor reference (never PII). @return this builder. */
        public Builder actorRef(String actorRef) {
            event.actorRef = actorRef;
            return this;
        }

        /** @param geoAreaId ward-or-coarser area public id. @return this builder. */
        public Builder geoAreaId(UUID geoAreaId) {
            event.geoAreaId = geoAreaId;
            return this;
        }

        /** @param categoryId issue-category public id. @return this builder. */
        public Builder categoryId(UUID categoryId) {
            event.categoryId = categoryId;
            return this;
        }

        /** @param tier the actor's trust tier. @return this builder. */
        public Builder tier(AnalyticsTier tier) {
            event.tier = tier;
            return this;
        }

        /** @param channel the origin channel. @return this builder. */
        public Builder channel(AnalyticsChannel channel) {
            event.channel = channel;
            return this;
        }

        /** @param activeRole the actor's active role/"hat". @return this builder. */
        public Builder activeRole(AnalyticsRole activeRole) {
            event.activeRole = activeRole;
            return this;
        }

        /** @param latencySeconds the headline latency measure in seconds. @return this builder. */
        public Builder latencySeconds(Long latencySeconds) {
            event.latencySeconds = latencySeconds;
            return this;
        }

        /** @param breachType the breached SLA clock. @return this builder. */
        public Builder breachType(BreachType breachType) {
            event.breachType = breachType;
            return this;
        }

        /** @param outcome a controlled-vocabulary outcome/qualifier code (never PII). @return this builder. */
        public Builder outcome(String outcome) {
            event.outcome = outcome;
            return this;
        }

        /** @return the assembled, not-yet-persisted fact. */
        public AnalyticsEvent build() {
            return event;
        }
    }
}
