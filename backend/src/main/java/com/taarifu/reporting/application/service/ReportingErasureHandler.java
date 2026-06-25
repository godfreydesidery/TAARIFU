package com.taarifu.reporting.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.privacy.api.event.ErasureRequested;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reporting's share of the PDPA ERASURE fan-out — severs the subject's reporter/actor linkage while keeping
 * the de-identified civic record (PRD §18, §25.1, UC-A17/UC-S09; ADR-0016 §5, ADR-0014).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers a privacy
 * {@link ErasureRequested} event to. For the named subject it (1) severs the {@code reporterProfileId} on
 * every report the subject filed, and (2) severs the {@code actorProfileId} on every case-event the subject
 * authored as the acting citizen. The reports and timeline entries <b>survive as an anonymised civic
 * record</b> (PRD §25.1: a report's civic content persists; the reporter linkage is severed) — counts,
 * ward-level issue history, and the append-only case timeline stay intact; only the tie to the now-erased
 * person is cut, exactly as an {@link Report#isAnonymous() anonymous} sensitive filing always reads.</p>
 *
 * <p><b>Append-only history is never broken</b> (PRD §25.1, ADR-0011): no report or case-event row is deleted
 * and no audit row is mutated — the handler only de-identifies a reference field and <b>appends</b> one
 * {@link AuditEventType#SUBJECT_DATA_ERASED} tombstone recording the reporting module severed its share.</p>
 *
 * <p><b>Idempotent (at-least-once, ADR-0014 §3):</b> the relay may redeliver the same {@code eventId}. The
 * sever is naturally idempotent — a second pass finds zero reports/events still linked to the subject
 * (they were nulled on the first pass), severs nothing, and skips the audit append (no second tombstone).</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the event payload carries ids only; the handler reads no PII from it,
 * operates solely on reporting's own aggregates via reporting's own repositories, and never calls back into
 * the producing (privacy) module. Citizen civic content (title/description/message) is preserved as the
 * anonymised record but never logged; logging is by subject reference + counts only (PRD §18, L-1).</p>
 */
@Component
public class ReportingErasureHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportingErasureHandler.class);

    private final ReportRepository reportRepository;
    private final CaseEventRepository caseEventRepository;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;
    private final SearchIndexApi searchIndexApi;

    /**
     * @param reportRepository    the subject's filed reports (reporter-linkage sever target).
     * @param caseEventRepository the subject's authored timeline entries (actor-linkage sever target).
     * @param audit               append-only audit writer (appends the {@code SUBJECT_DATA_ERASED} tombstone —
     *                            never mutates the hash-chain, L-1).
     * @param objectMapper        shared Jackson mapper; converts the relay's tree payload back into the typed
     *                            {@link ErasureRequested} record (the relay is payload-agnostic).
     * @param searchIndexApi      the search module's published inbound port (ADR-0017 §1). A severed report
     *                            becomes {@link Report#isAnonymous() anonymous}, so it must be pulled from public
     *                            discovery — the handler removes its discovery row as part of the erasure.
     */
    public ReportingErasureHandler(ReportRepository reportRepository,
                                   CaseEventRepository caseEventRepository,
                                   AuditEventService audit,
                                   ObjectMapper objectMapper,
                                   SearchIndexApi searchIndexApi) {
        this.reportRepository = reportRepository;
        this.caseEventRepository = caseEventRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.searchIndexApi = searchIndexApi;
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
     * Handles a delivered erasure event: sever the subject's reporter/actor linkage, idempotently.
     *
     * <p>Runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so reporting's severing commits
     * (or rolls back) as a unit, isolated from the relay's batch and from sibling modules' handlers — a fault
     * here fails only this row (retry → DLQ), never another module's severing.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the at-least-once idempotency key (the
     *              effect is also idempotent by construction — a re-pass finds nothing still linked).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope<?> event) {
        ErasureRequested payload = objectMapper.convertValue(event.payload(), ErasureRequested.class);
        UUID subjectPublicId = payload.subjectPublicId();

        List<Report> reports = reportRepository.findAllByReporterProfileId(subjectPublicId);
        for (Report report : reports) {
            report.anonymiseReporter();
            // SEARCH (ADR-0017 §1): a severed report is now anonymous, so it must not be discoverable — pull its
            // public-discovery row. Idempotent: removing an absent row (it was PRIVATE/anonymous and never
            // indexed, or already removed) is a no-op, so this stays at-least-once safe alongside the sever.
            searchIndexApi.remove(SearchEntityType.PUBLIC_REPORT, report.getPublicId());
        }
        List<CaseEvent> events = caseEventRepository.findByActorProfileId(subjectPublicId);
        for (CaseEvent caseEvent : events) {
            caseEvent.anonymiseActor();
        }

        if (reports.isEmpty() && events.isEmpty()) {
            // Nothing still linked: subject never reported here, or a prior delivery already severed it —
            // idempotent no-op, no second tombstone audit (ADR-0014 §3).
            log.debug("Reporting erasure: nothing to sever for subject reference (eventId={})", event.eventId());
            return;
        }

        // Append the per-module severing tombstone — the audit hash-chain is EXTENDED, never broken (§25.1).
        // References + counts only; actor = SYSTEM-as-subject (self-initiated erasure), subject = the account.
        audit.record(AuditEvent.Builder
                .of(AuditEventType.SUBJECT_DATA_ERASED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId)
                .reason("reporting:reports=" + reports.size() + ",events=" + events.size()
                        + ",DSR:" + payload.dsrPublicId())
                .build());

        log.info("Reporting erasure: severed reporter/actor linkage (reports={}, events={}) for subject "
                + "reference (eventId={})", reports.size(), events.size(), event.eventId());
    }
}
