package com.taarifu.engagement.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.DomainEventHandler;
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
 * Engagement's share of the PDPA ERASURE fan-out — de-identifies the subject's civic-engagement footprint
 * (created petitions, signatures, questions, survey responses) while keeping every binding civic act counted
 * (PRD §18, §25.1, §23.5, UC-A17/UC-S09; ADR-0016 §5, ADR-0014).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers a privacy
 * {@link ErasureRequested} event to. For the named subject it severs the authoring/signing/asking/responding
 * linkage on each engagement aggregate the subject owns:
 * <ul>
 *   <li><b>created petitions</b> → {@code creatorProfileId} nulled (the petition survives as an anonymised
 *       civic record, exactly like an org-authored petition);</li>
 *   <li><b>signatures</b> → signer replaced by a <b>deterministic tombstone</b> + comment cleared (the
 *       signature still counts toward the tally — wealth/erasure must not rewrite a democratic count, §23.5);</li>
 *   <li><b>questions</b> → asker replaced by the same tombstone (the public Q&amp;A record survives);</li>
 *   <li><b>survey responses</b> → responder replaced by the tombstone (the response survives as an anonymised
 *       data point in the survey aggregate).</li>
 * </ul>
 *
 * <p><b>Counts are never rewritten</b> (§23.5 integrity fence): the handler de-identifies references, it never
 * deletes a signature, decrements a petition tally, or removes a survey response — erasure severs the person,
 * not the civic act.</p>
 *
 * <p><b>Tombstone determinism + idempotency (ADR-0014 §3):</b> the signer/asker/responder columns are
 * {@code NOT NULL} (and the signer/responder are unique keys), so the linkage is replaced by
 * {@link #tombstoneOf(UUID)} — a stable, non-account UUID derived from the subject id. Because the lookups
 * match on the subject's <b>real</b> account id, a redelivery finds zero rows still on it (the first pass
 * rewrote them to the tombstone), severs nothing, and skips the audit append.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the event payload carries ids only; the handler reads no PII from it,
 * operates solely on engagement's own aggregates via engagement's own repositories, and never calls back into
 * the producing (privacy) module. Logging is by subject reference + counts only (PRD §18, L-1).</p>
 */
@Component
public class EngagementErasureHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(EngagementErasureHandler.class);

    private final PetitionRepository petitionRepository;
    private final PetitionSignatureRepository signatureRepository;
    private final QuestionRepository questionRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;

    /**
     * @param petitionRepository       the subject's authored petitions (creator-linkage sever target).
     * @param signatureRepository      the subject's signatures (signer-linkage de-identify target).
     * @param questionRepository       the subject's asked questions (asker-linkage de-identify target).
     * @param surveyResponseRepository the subject's survey responses (responder-linkage de-identify target).
     * @param audit                    append-only audit writer ({@code SUBJECT_DATA_ERASED} tombstone).
     * @param objectMapper             shared Jackson mapper; decodes the relay's tree payload.
     */
    public EngagementErasureHandler(PetitionRepository petitionRepository,
                                    PetitionSignatureRepository signatureRepository,
                                    QuestionRepository questionRepository,
                                    SurveyResponseRepository surveyResponseRepository,
                                    AuditEventService audit,
                                    ObjectMapper objectMapper) {
        this.petitionRepository = petitionRepository;
        this.signatureRepository = signatureRepository;
        this.questionRepository = questionRepository;
        this.surveyResponseRepository = surveyResponseRepository;
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
     * Handles a delivered erasure event: de-identify the subject's engagement footprint, idempotently.
     *
     * <p>Runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so engagement's severing commits
     * (or rolls back) as a unit, isolated from the relay batch and sibling handlers.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the at-least-once idempotency key.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope<?> event) {
        ErasureRequested payload = objectMapper.convertValue(event.payload(), ErasureRequested.class);
        UUID subjectPublicId = payload.subjectPublicId();
        UUID tombstone = tombstoneOf(subjectPublicId);

        List<Petition> petitions = petitionRepository.findByCreatorProfileId(subjectPublicId);
        for (Petition petition : petitions) {
            petition.anonymiseCreator();
        }
        List<PetitionSignature> signatures = signatureRepository.findBySignerProfileId(subjectPublicId);
        for (PetitionSignature signature : signatures) {
            signature.anonymiseSigner(tombstone);
        }
        List<Question> questions = questionRepository.findByAskerProfileId(subjectPublicId);
        for (Question question : questions) {
            question.anonymiseAsker(tombstone);
        }
        List<SurveyResponse> responses = surveyResponseRepository.findByResponderProfileId(subjectPublicId);
        for (SurveyResponse response : responses) {
            response.anonymiseResponder(tombstone);
        }

        int total = petitions.size() + signatures.size() + questions.size() + responses.size();
        if (total == 0) {
            // Nothing still linked to the real subject id — subject never engaged here, or a prior delivery
            // already de-identified it: idempotent no-op, no second tombstone audit (ADR-0014 §3).
            log.debug("Engagement erasure: nothing to sever for subject reference (eventId={})", event.eventId());
            return;
        }

        // Append the per-module severing tombstone — the audit hash-chain is EXTENDED, never broken (§25.1).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.SUBJECT_DATA_ERASED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId)
                .reason("engagement:petitions=" + petitions.size() + ",signatures=" + signatures.size()
                        + ",questions=" + questions.size() + ",surveyResponses=" + responses.size()
                        + ",DSR:" + payload.dsrPublicId())
                .build());

        log.info("Engagement erasure: de-identified footprint (petitions={}, signatures={}, questions={}, "
                + "surveyResponses={}) for subject reference (eventId={})", petitions.size(), signatures.size(),
                questions.size(), responses.size(), event.eventId());
    }

    /**
     * The deterministic, non-account per-subject tombstone token that replaces a {@code NOT NULL} signer/
     * asker/responder reference on erasure.
     *
     * <p>WHY a name-based (v3) UUID over {@code "erased:" + subjectPublicId}: it is <b>stable</b> for a given
     * subject (so a redelivery produces the same token — the effect is idempotent), it is <b>not</b> the
     * subject's account id (so the person is unrecoverable from the de-identified row), and it preserves the
     * {@code NOT NULL} + uniqueness invariants the count-preserving design depends on (§23.5).</p>
     *
     * @param subjectPublicId the erased subject's account public id.
     * @return a stable tombstone UUID, distinct from any real account id.
     */
    private static UUID tombstoneOf(UUID subjectPublicId) {
        return UUID.nameUUIDFromBytes(("erased:engagement:" + subjectPublicId).getBytes(StandardCharsets.UTF_8));
    }
}
