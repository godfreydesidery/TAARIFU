package com.taarifu.engagement.application.service;

import com.taarifu.engagement.api.dto.EngagementExportView;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.PetitionSignature;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.SurveyResponse;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.engagement.domain.repository.PetitionSignatureRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
import com.taarifu.engagement.domain.repository.SurveyResponseRepository;
import com.taarifu.privacy.api.SubjectExportContributor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Engagement's contribution to a data-subject ACCESS export — the subject's petitions, signatures, questions
 * and survey responses (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: implements the privacy module's {@link SubjectExportContributor} SPI so the privacy
 * export aggregator can include engagement's data <b>without</b> reaching into engagement's internals
 * (ADR-0013). Registered automatically as a Spring bean; the privacy {@code SubjectDataExportService} injects
 * every contributor and composes the export by {@link #section()}.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, ADR-0016 §4):</b> returns {@link EngagementExportView} — the subject's
 * own civic acts and their own content (signature comment, question body, answers payload). It never
 * enumerates other signers/responders or another subject's data.</p>
 */
@Service
public class EngagementExportContributor implements SubjectExportContributor {

    /** The export section key engagement fills. */
    private static final String SECTION = "engagement";

    private final PetitionRepository petitionRepository;
    private final PetitionSignatureRepository signatureRepository;
    private final QuestionRepository questionRepository;
    private final SurveyResponseRepository surveyResponseRepository;

    /**
     * @param petitionRepository       the subject's authored petitions.
     * @param signatureRepository      the subject's signatures.
     * @param questionRepository       the subject's asked questions.
     * @param surveyResponseRepository the subject's survey responses.
     */
    public EngagementExportContributor(PetitionRepository petitionRepository,
                                       PetitionSignatureRepository signatureRepository,
                                       QuestionRepository questionRepository,
                                       SurveyResponseRepository surveyResponseRepository) {
        this.petitionRepository = petitionRepository;
        this.signatureRepository = signatureRepository;
        this.questionRepository = questionRepository;
        this.surveyResponseRepository = surveyResponseRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String section() {
        return SECTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the subject's engagement footprint, or {@code null} if the subject has none (so the section
     * is simply absent from the export).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Object contribute(UUID subjectPublicId) {
        List<Petition> petitions = petitionRepository.findByCreatorProfileId(subjectPublicId);
        List<PetitionSignature> signatures = signatureRepository.findBySignerProfileId(subjectPublicId);
        List<Question> questions = questionRepository.findByAskerProfileId(subjectPublicId);
        List<SurveyResponse> responses = surveyResponseRepository.findByResponderProfileId(subjectPublicId);

        if (petitions.isEmpty() && signatures.isEmpty() && questions.isEmpty() && responses.isEmpty()) {
            return null;
        }

        List<EngagementExportView.CreatedPetition> createdPetitions = petitions.stream()
                .map(p -> new EngagementExportView.CreatedPetition(
                        p.getPublicId(), p.getTitle(), p.getStatus().name(), p.getCreatedAt()))
                .toList();
        List<EngagementExportView.Signature> sigs = signatures.stream()
                .map(s -> new EngagementExportView.Signature(
                        s.getPetition().getPublicId(), s.getComment(), s.isPublicSignature(), s.getCreatedAt()))
                .toList();
        List<EngagementExportView.AskedQuestion> asked = questions.stream()
                .map(q -> new EngagementExportView.AskedQuestion(
                        q.getPublicId(), q.getBody(), q.getStatus().name(), q.getCreatedAt()))
                .toList();
        List<EngagementExportView.SurveyResponseItem> surveyResponses = responses.stream()
                .map(r -> new EngagementExportView.SurveyResponseItem(
                        r.getPublicId(), r.getSurvey().getPublicId(), r.getAnswers(), r.getCreatedAt()))
                .toList();

        return new EngagementExportView(createdPetitions, sigs, asked, surveyResponses);
    }
}
