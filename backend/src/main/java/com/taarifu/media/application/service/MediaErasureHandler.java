package com.taarifu.media.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Media's share of the PDPA ERASURE fan-out — severs the uploader linkage on the subject's uploaded objects
 * (PRD §18, §25.1, UC-A17/UC-S09; ADR-0016 §5, ADR-0014).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers a privacy
 * {@link ErasureRequested} event to. For the named subject it nulls the {@code uploadedByProfileId} on every
 * media object the subject uploaded — cutting the personal uploader reference while leaving the object record
 * to its host resource's own lifecycle (a report's attachment survives or is anonymised with the host civic
 * record, which the reporting/host module owns; the EXIF/geo strip already removed embedded location PII from
 * the bytes at promote time — EI-8).</p>
 *
 * <p><b>Append-only history is never broken</b> (§25.1, ADR-0011): no audit row is mutated — the handler nulls
 * a reference field and <b>appends</b> one {@link AuditEventType#SUBJECT_DATA_ERASED} tombstone.</p>
 *
 * <p><b>Idempotent (at-least-once, ADR-0014 §3):</b> the sever is naturally idempotent — a second pass finds
 * zero objects still linked to the subject (their uploader was nulled on the first pass) and skips the audit
 * append.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the event payload carries ids only; the handler reads no PII from it,
 * operates solely on media's own aggregate via media's own repository, and never calls back into the
 * producing (privacy) module. Logging is by subject reference + counts only (PRD §18, L-1).</p>
 */
@Component
public class MediaErasureHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaErasureHandler.class);

    private final MediaObjectRepository mediaObjectRepository;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;

    /**
     * @param mediaObjectRepository the subject's uploaded objects (uploader-linkage sever target).
     * @param audit                 append-only audit writer ({@code SUBJECT_DATA_ERASED} tombstone).
     * @param objectMapper          shared Jackson mapper; decodes the relay's tree payload.
     */
    public MediaErasureHandler(MediaObjectRepository mediaObjectRepository,
                               AuditEventService audit,
                               ObjectMapper objectMapper) {
        this.mediaObjectRepository = mediaObjectRepository;
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
     * Handles a delivered erasure event: sever the uploader linkage on the subject's objects, idempotently.
     *
     * <p>Runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so media's severing commits (or
     * rolls back) as a unit, isolated from the relay batch and sibling handlers.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the at-least-once idempotency key.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope<?> event) {
        ErasureRequested payload = objectMapper.convertValue(event.payload(), ErasureRequested.class);
        UUID subjectPublicId = payload.subjectPublicId();

        List<MediaObject> objects = mediaObjectRepository.findByUploadedByProfileId(subjectPublicId);
        for (MediaObject object : objects) {
            object.severUploader();
        }

        if (objects.isEmpty()) {
            // Nothing still linked: subject uploaded nothing, or a prior delivery already severed it —
            // idempotent no-op, no second tombstone audit (ADR-0014 §3).
            log.debug("Media erasure: nothing to sever for subject reference (eventId={})", event.eventId());
            return;
        }

        // Append the per-module severing tombstone — the audit hash-chain is EXTENDED, never broken (§25.1).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.SUBJECT_DATA_ERASED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId)
                .reason("media:objects=" + objects.size() + ",DSR:" + payload.dsrPublicId())
                .build());

        log.info("Media erasure: severed uploader linkage (objects={}) for subject reference (eventId={})",
                objects.size(), event.eventId());
    }
}
