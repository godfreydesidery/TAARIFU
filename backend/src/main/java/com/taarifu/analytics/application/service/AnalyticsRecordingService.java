package com.taarifu.analytics.application.service;

import com.taarifu.analytics.api.AnalyticsApi;
import com.taarifu.analytics.api.RecordEventCommand;
import com.taarifu.analytics.domain.model.AnalyticsEvent;
import com.taarifu.analytics.domain.repository.AnalyticsEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Default implementation of the public {@link AnalyticsApi} — the idempotent recorder that appends one
 * {@link AnalyticsEvent} per occurrence (M15; PRD Appendix E; ADR-0013 — the {@code api} package is the
 * only cross-module surface, impl lives in {@code application.service}).
 *
 * <p>Responsibility: validates the command minimally, defaults the idempotency key + occurred-at, and
 * appends the fact exactly once. It is the seam {@code AnalyticsEventHandler} (the outbox consumer) calls when
 * the relay delivers a {@code CIVIC_ACTIVITY_RECORDED} event emitted by a sibling module — the outbox
 * increment is built and live (ADR-0013 §2), so this recorder runs asynchronously off the relay thread, never
 * on a citizen's critical path.</p>
 *
 * <p>WHY two-layer idempotency (pre-check + unique-constraint catch): an {@code existsByEventId} pre-check
 * keeps the common replay cheap, but two concurrent workers could both pass it; the globally-unique
 * {@code uq_analytics_event_event_id} index is the authoritative guard, and a
 * {@link DataIntegrityViolationException} on insert is swallowed as a successful no-op — so there is no
 * double-count even under concurrent at-least-once delivery (Appendix E.0/E.3, the same discipline as the
 * token ledger).</p>
 *
 * <p>WHY no PII handling here: the {@link RecordEventCommand} contract already excludes PII by construction
 * (Appendix E.4); this service trusts the caller's {@code actorRef} is a salted hash and records the
 * dimensions verbatim. It logs nothing identifying (PRD §18).</p>
 */
@Service
public class AnalyticsRecordingService implements AnalyticsApi {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRecordingService.class);

    private final AnalyticsEventRepository events;

    /**
     * @param events the append-only analytics-event repository.
     */
    public AnalyticsRecordingService(AnalyticsEventRepository events) {
        this.events = events;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the command, its {@code eventType} are missing (a programming
     *                                  error at a call site, not a runtime condition to swallow).
     */
    @Override
    @Transactional
    public boolean record(RecordEventCommand command) {
        if (command == null || command.eventType() == null) {
            throw new IllegalArgumentException("RecordEventCommand and its eventType are required");
        }
        UUID eventId = command.eventId() != null ? command.eventId() : UUID.randomUUID();

        // Cheap fast-path for the common replay; the unique index below is the authoritative guard.
        if (events.existsByEventId(eventId)) {
            return false;
        }

        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : Instant.now();
        AnalyticsEvent event = AnalyticsEvent.Builder.of(command.eventType(), occurredAt)
                .eventId(eventId)
                .actorRef(command.actorRef())
                .geoAreaId(command.geoAreaId())
                .categoryId(command.categoryId())
                .tier(command.tier())
                .channel(command.channel())
                .activeRole(command.activeRole())
                .latencySeconds(command.latencySeconds())
                .breachType(command.breachType())
                .outcome(command.outcome())
                .build();

        try {
            events.save(event);
            return true;
        } catch (DataIntegrityViolationException raced) {
            // A concurrent worker recorded the same eventId between the pre-check and the insert: the
            // unique constraint fired. That is the idempotency guarantee working — treat it as a no-op.
            log.debug("Duplicate analytics event {} swallowed (idempotent no-op)", eventId);
            return false;
        }
    }
}
