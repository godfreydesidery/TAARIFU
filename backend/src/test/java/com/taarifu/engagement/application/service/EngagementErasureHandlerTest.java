package com.taarifu.engagement.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.PetitionSignature;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.SurveyResponse;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.engagement.domain.repository.PetitionSignatureRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
import com.taarifu.engagement.domain.repository.SurveyResponseRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EngagementErasureHandler} — engagement's share of the PDPA ERASURE fan-out
 * (PRD §25.1, §23.5, UC-A17/UC-S09; ADR-0016 §5).
 *
 * <p>Proves the load-bearing invariants:</p>
 * <ul>
 *   <li><b>Creator linkage nulled</b> on authored petitions (civic record kept);</li>
 *   <li><b>Signer/asker/responder de-identified with a deterministic, non-account tombstone</b> — the binding
 *       civic act stays counted (§23.5: erasure must not rewrite a democratic tally); the token is stable
 *       across a redelivery and is NOT the subject's account id;</li>
 *   <li><b>One {@code SUBJECT_DATA_ERASED} audit row APPENDED</b> with references + counts;</li>
 *   <li><b>Idempotent:</b> nothing linked → no-op (no second audit row).</li>
 * </ul>
 * Mockito only; no Docker.
 */
@ExtendWith(MockitoExtension.class)
class EngagementErasureHandlerTest {

    @Mock
    private PetitionRepository petitionRepository;
    @Mock
    private PetitionSignatureRepository signatureRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private SurveyResponseRepository surveyResponseRepository;
    @Mock
    private AuditEventService audit;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID subject = UUID.randomUUID();
    private final UUID dsr = UUID.randomUUID();

    private EngagementErasureHandler handler() {
        return new EngagementErasureHandler(petitionRepository, signatureRepository, questionRepository,
                surveyResponseRepository, audit, objectMapper);
    }

    private EventEnvelope<?> event() {
        return new EventEnvelope<>(UUID.randomUUID(), ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE, dsr, new ErasureRequested(subject, dsr),
                Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void handle_deidentifiesFootprint_withDeterministicNonAccountTombstone_appendsAudit() {
        Petition petition = mock(Petition.class);
        PetitionSignature signature = mock(PetitionSignature.class);
        Question question = mock(Question.class);
        SurveyResponse response = mock(SurveyResponse.class);
        when(petitionRepository.findByCreatorProfileId(subject)).thenReturn(List.of(petition));
        when(signatureRepository.findBySignerProfileId(subject)).thenReturn(List.of(signature));
        when(questionRepository.findByAskerProfileId(subject)).thenReturn(List.of(question));
        when(surveyResponseRepository.findByResponderProfileId(subject)).thenReturn(List.of(response));

        handler().handle(event());

        // Creator severed; binding acts de-identified with a stable token that is NOT the account id.
        verify(petition).anonymiseCreator();
        ArgumentCaptor<UUID> sigTok = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> askTok = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> respTok = ArgumentCaptor.forClass(UUID.class);
        verify(signature).anonymiseSigner(sigTok.capture());
        verify(question).anonymiseAsker(askTok.capture());
        verify(response).anonymiseResponder(respTok.capture());
        assertThat(sigTok.getValue()).isNotNull().isNotEqualTo(subject)
                .isEqualTo(askTok.getValue()).isEqualTo(respTok.getValue());

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.SUBJECT_DATA_ERASED);
        assertThat(ev.getValue().getReasonCode())
                .contains("engagement:petitions=1,signatures=1,questions=1,surveyResponses=1")
                .contains("DSR:" + dsr);
    }

    @Test
    void handle_tombstoneIsStableAcrossRedelivery() {
        // Capture the token on a first delivery, then a second; determinism means they MUST be identical
        // (the redelivery idempotency contract depends on it — ADR-0014 §3).
        PetitionSignature first = mock(PetitionSignature.class);
        PetitionSignature second = mock(PetitionSignature.class);
        lenient().when(petitionRepository.findByCreatorProfileId(subject)).thenReturn(List.of());
        lenient().when(questionRepository.findByAskerProfileId(subject)).thenReturn(List.of());
        lenient().when(surveyResponseRepository.findByResponderProfileId(subject)).thenReturn(List.of());
        when(signatureRepository.findBySignerProfileId(subject))
                .thenReturn(List.of(first)).thenReturn(List.of(second));

        handler().handle(event());
        handler().handle(event());

        ArgumentCaptor<UUID> t1 = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> t2 = ArgumentCaptor.forClass(UUID.class);
        verify(first).anonymiseSigner(t1.capture());
        verify(second).anonymiseSigner(t2.capture());
        assertThat(t1.getValue()).isEqualTo(t2.getValue());
    }

    @Test
    void handle_nothingLinked_isIdempotentNoOp() {
        when(petitionRepository.findByCreatorProfileId(subject)).thenReturn(List.of());
        when(signatureRepository.findBySignerProfileId(subject)).thenReturn(List.of());
        when(questionRepository.findByAskerProfileId(subject)).thenReturn(List.of());
        when(surveyResponseRepository.findByResponderProfileId(subject)).thenReturn(List.of());

        handler().handle(event());

        verify(audit, never()).record(any(AuditEvent.class));
    }
}
