package com.taarifu.accountability.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Accountability's share of the PDPA ERASURE fan-out — de-identifies the subject's ratings while keeping each
 * rating counted toward the representative's aggregate (PRD §18, §25.1, §23.5, UC-A17/UC-S09; ADR-0016 §5,
 * ADR-0014).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers a privacy
 * {@link ErasureRequested} event to. For the named subject it replaces the {@code raterProfileId} on every
 * rating the subject gave with a deterministic per-subject tombstone and clears any free-text comment. The
 * rating <b>survives as a counted accountability score</b> — the computed aggregate (a faithful function of
 * the append-only rows, §23) is unchanged; only the tie to the now-erased rater is severed.</p>
 *
 * <p><b>The aggregate is never rewritten</b> (§23.5 integrity fence): the handler de-identifies the rater
 * reference, it never deletes a rating row — erasure severs the person, not the democratic-weight act. No
 * audit row is mutated; the handler <b>appends</b> one {@link AuditEventType#SUBJECT_DATA_ERASED} tombstone.</p>
 *
 * <p><b>Tombstone determinism + idempotency (ADR-0014 §3):</b> {@code raterProfileId} is {@code NOT NULL} and
 * part of the one-per-person unique key, so the linkage is replaced by {@link #tombstoneOf(UUID)} — a stable,
 * non-account UUID. The lookup matches on the subject's <b>real</b> account id, so a redelivery finds zero
 * rows still on it (the first pass rewrote them) and skips the audit append.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the event payload carries ids only; the handler reads no PII from it,
 * operates solely on accountability's own aggregate via its own repository, and never reads a token balance
 * on this path (the fence, §23). Logging is by subject reference + counts only (PRD §18, L-1).</p>
 */
@Component
public class AccountabilityErasureHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountabilityErasureHandler.class);

    private final RatingRepository ratingRepository;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;

    /**
     * @param ratingRepository the subject's ratings (rater-linkage de-identify target).
     * @param audit            append-only audit writer ({@code SUBJECT_DATA_ERASED} tombstone).
     * @param objectMapper     shared Jackson mapper; decodes the relay's tree payload.
     */
    public AccountabilityErasureHandler(RatingRepository ratingRepository,
                                        AuditEventService audit,
                                        ObjectMapper objectMapper) {
        this.ratingRepository = ratingRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * @return the single taxonomy key {@link ErasureRequested#EVENT_TYPE} this handler consumes.
     */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ErasureRequested.EVENT_TYPE);
    }

    /**
     * Handles a delivered erasure event: de-identify the subject's ratings, idempotently.
     *
     * <p>Runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so accountability's severing
     * commits (or rolls back) as a unit, isolated from the relay batch and sibling handlers.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the at-least-once idempotency key.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope<?> event) {
        ErasureRequested payload = objectMapper.convertValue(event.payload(), ErasureRequested.class);
        UUID subjectPublicId = payload.subjectPublicId();
        UUID tombstone = tombstoneOf(subjectPublicId);

        List<Rating> ratings = ratingRepository.findByRaterProfileId(subjectPublicId);
        for (Rating rating : ratings) {
            rating.anonymiseRater(tombstone);
        }

        if (ratings.isEmpty()) {
            // Nothing still linked to the real subject id — subject never rated, or a prior delivery already
            // de-identified it: idempotent no-op, no second tombstone audit (ADR-0014 §3).
            log.debug("Accountability erasure: nothing to sever for subject reference (eventId={})",
                    event.eventId());
            return;
        }

        // Append the per-module severing tombstone — the audit hash-chain is EXTENDED, never broken (§25.1).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.SUBJECT_DATA_ERASED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId)
                .reason("accountability:ratings=" + ratings.size() + ",DSR:" + payload.dsrPublicId())
                .build());

        log.info("Accountability erasure: de-identified ratings (ratings={}) for subject reference "
                + "(eventId={})", ratings.size(), event.eventId());
    }

    /**
     * The deterministic, non-account per-subject tombstone token that replaces the {@code NOT NULL} rater
     * reference on erasure — stable per subject (so a redelivery is idempotent), distinct from any account id
     * (so the rater is unrecoverable), and uniqueness-preserving (so the one-per-person key still holds).
     *
     * @param subjectPublicId the erased subject's account public id.
     * @return a stable tombstone UUID, distinct from any real account id.
     */
    private static UUID tombstoneOf(UUID subjectPublicId) {
        return UUID.nameUUIDFromBytes(
                ("erased:accountability:" + subjectPublicId).getBytes(StandardCharsets.UTF_8));
    }
}
