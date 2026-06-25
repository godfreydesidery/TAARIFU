package com.taarifu.reporting.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.privacy.api.event.ErasureRequested;
import com.taarifu.reporting.domain.model.CaseEvent;
import com.taarifu.reporting.domain.model.Report;
import com.taarifu.reporting.domain.repository.CaseEventRepository;
import com.taarifu.reporting.domain.repository.ReportRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportingErasureHandler} — reporting's share of the PDPA ERASURE fan-out
 * (PRD §25.1, UC-A17/UC-S09; ADR-0016 §5).
 *
 * <p>Proves the load-bearing invariants a reviewer must never see regress:</p>
 * <ul>
 *   <li><b>Reporter linkage severed:</b> every report the subject filed has {@code reporterProfileId} nulled —
 *       the civic content survives as an anonymised record (PRD §25.1);</li>
 *   <li><b>Actor linkage severed:</b> every case event the subject authored has {@code actorProfileId} nulled;</li>
 *   <li><b>Audit appended, never mutated:</b> exactly one {@code SUBJECT_DATA_ERASED} row is APPENDED with a
 *       references+counts reason — the hash-chain is extended, never broken;</li>
 *   <li><b>Idempotent:</b> a redelivery with nothing still linked is a no-op (no second audit row).</li>
 * </ul>
 * Mockito only; no Docker.
 */
@ExtendWith(MockitoExtension.class)
class ReportingErasureHandlerTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private CaseEventRepository caseEventRepository;
    @Mock
    private AuditEventService audit;
    @Mock
    private SearchIndexApi searchIndexApi;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID subject = UUID.randomUUID();
    private final UUID dsr = UUID.randomUUID();

    private ReportingErasureHandler handler() {
        return new ReportingErasureHandler(reportRepository, caseEventRepository, audit, objectMapper,
                searchIndexApi);
    }

    private EventEnvelope<?> event() {
        return new EventEnvelope<>(UUID.randomUUID(), ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE, dsr, new ErasureRequested(subject, dsr),
                Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void handle_seversReporterAndActorLinkage_appendsAuditTombstone() {
        Report report = mock(Report.class);
        UUID reportPublicId = UUID.randomUUID();
        when(report.getPublicId()).thenReturn(reportPublicId);
        CaseEvent caseEvent = mock(CaseEvent.class);
        when(reportRepository.findAllByReporterProfileId(subject)).thenReturn(List.of(report));
        when(caseEventRepository.findByActorProfileId(subject)).thenReturn(List.of(caseEvent));

        handler().handle(event());

        // Reporter linkage and actor linkage severed (civic record kept).
        verify(report).anonymiseReporter();
        verify(caseEvent).anonymiseActor();

        // SEARCH (ADR-0017 §1): the now-anonymous report is pulled from public discovery. Fails if the
        // erasure stops removing the discovery row (a leak: a de-identified report still surfacing in search).
        verify(searchIndexApi).remove(SearchEntityType.PUBLIC_REPORT, reportPublicId);

        // Exactly one SUBJECT_DATA_ERASED tombstone APPENDED, with references + counts (no PII).
        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.SUBJECT_DATA_ERASED);
        assertThat(ev.getValue().getSubjectPublicId()).isEqualTo(subject);
        assertThat(ev.getValue().getReasonCode())
                .contains("reporting:reports=1,events=1")
                .contains("DSR:" + dsr);
    }

    @Test
    void handle_nothingLinked_isIdempotentNoOp() {
        when(reportRepository.findAllByReporterProfileId(subject)).thenReturn(List.of());
        when(caseEventRepository.findByActorProfileId(subject)).thenReturn(List.of());

        handler().handle(event());

        // No audit row when nothing was severed (redelivery / subject never reported here).
        verify(audit, never()).record(any(AuditEvent.class));
    }
}
