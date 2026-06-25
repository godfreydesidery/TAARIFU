package com.taarifu.engagement.application.service.search;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.application.service.PetitionService;
import com.taarifu.engagement.application.service.QuestionService;
import com.taarifu.engagement.application.service.SurveyService;
import com.taarifu.engagement.domain.model.Petition;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.enums.PetitionStatus;
import com.taarifu.engagement.domain.model.enums.PetitionTargetType;
import com.taarifu.engagement.domain.model.enums.QuestionStatus;
import com.taarifu.engagement.domain.model.enums.SurveyStatus;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import com.taarifu.engagement.domain.repository.AnswerRepository;
import com.taarifu.engagement.domain.repository.PetitionRepository;
import com.taarifu.engagement.domain.repository.PetitionSignatureRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
import com.taarifu.engagement.domain.repository.SurveyRepository;
import com.taarifu.engagement.domain.repository.SurveyResponseRepository;
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.identity.api.ProfileLookupApi;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for engagement's three {@link com.taarifu.search.domain.port.SearchBackfillSource} adapters —
 * {@link PetitionBackfillSource}, {@link SurveyBackfillSource}, {@link QuestionBackfillSource}.
 *
 * <p>Responsibility: pin the discovery-backfill invariants a reviewer must never see silently regress — a
 * publicly-visible petition/poll/question is re-pushed with the public-safe projection; a non-public row routed
 * through the shared fence is <b>never</b> upserted (it is removed, idempotent) and not counted; the source scan is
 * batched across pages; the upsert count reflects only the rows actually indexed; and the asker id is never
 * surfaced for a question (PRD §18).</p>
 *
 * <p><b>WHY a REAL service per adapter (not a mock):</b> the whole point of an adapter is that the backfill reuses
 * the live producer's fence + projection so they cannot drift (ADR-0017 §1, DRY). Mocking
 * {@code reindexForDiscovery} would test a stub, not the fence. So this test wires the genuine
 * {@link PetitionService}/{@link SurveyService}/{@link QuestionService} (with a mocked {@link SearchIndexApi} and
 * repositories) and asserts on the <b>real</b> projection that flows out — exactly what production indexes. Mockito
 * only — no database.</p>
 */
class EngagementSearchBackfillSourceTest {

    private final EngagementMapper mapper = new EngagementMapper();
    private SearchIndexApi searchIndex;

    private PetitionRepository petitions;
    private SurveyRepository surveys;
    private QuestionRepository questions;

    private PetitionBackfillSource petitionBackfill;
    private SurveyBackfillSource surveyBackfill;
    private QuestionBackfillSource questionBackfill;

    @BeforeEach
    void setUp() {
        searchIndex = mock(SearchIndexApi.class);

        petitions = mock(PetitionRepository.class);
        surveys = mock(SurveyRepository.class);
        questions = mock(QuestionRepository.class);

        // REAL services so the genuine reindexForDiscovery fence + projection is exercised; only collaborators
        // the backfill never touches are stubbed. ProfileLookupApi is unused on the reindex path (authorship is
        // already stored on the row), so a bare mock is fine.
        PetitionService petitionService = new PetitionService(petitions, mock(PetitionSignatureRepository.class),
                mapper, mock(ScopeGuard.class), mock(RepresentativeQueryApi.class), mock(ElectoralScopeApi.class),
                mock(AuditEventService.class), mock(OutboxWriter.class), searchIndex, mock(ProfileLookupApi.class));
        SurveyService surveyService = new SurveyService(surveys, mock(SurveyResponseRepository.class), mapper,
                mock(OutboxWriter.class), searchIndex, mock(ProfileLookupApi.class));
        QuestionService questionService = new QuestionService(questions, mock(AnswerRepository.class), mapper,
                mock(ScopeGuard.class), mock(AuditEventService.class), searchIndex, mock(ProfileLookupApi.class));

        petitionBackfill = new PetitionBackfillSource(petitions, petitionService);
        surveyBackfill = new SurveyBackfillSource(surveys, surveyService);
        questionBackfill = new QuestionBackfillSource(questions, questionService);
    }

    // ----- entityType -----

    @Test
    void entityTypes_areOnePerAdapter() {
        // The orchestrator groups results by this; one adapter owns exactly one type (ADR-0017).
        assertThat(petitionBackfill.entityType()).isEqualTo(SearchEntityType.PETITION);
        assertThat(surveyBackfill.entityType()).isEqualTo(SearchEntityType.POLL);
        assertThat(questionBackfill.entityType()).isEqualTo(SearchEntityType.QUESTION);
    }

    // ----- PETITION -----

    @Test
    void petitionBackfill_upsertsPublicPetitions_withPublicSafeProjection_andCountsThem() {
        Petition active = activePetition("Repair the Kata road", "Pothole everywhere");
        Petition succeeded = activePetition("Build a clinic", "We need a dispensary");
        succeeded.registerSignature(); // remains public; just exercises a populated row
        when(petitions.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(active, succeeded), PageRequest.of(0, 200), 2));

        long upserted = petitionBackfill.backfill(searchIndex);

        assertThat(upserted).isEqualTo(2L);
        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex, times(2)).upsert(captor.capture());
        verify(searchIndex, never()).remove(any(), any());
        SearchDocumentUpsert first = captor.getAllValues().get(0);
        assertThat(first.entityType()).isEqualTo(SearchEntityType.PETITION);
        assertThat(first.entityPublicId()).isEqualTo(active.getPublicId());
        assertThat(first.title()).isEqualTo("Repair the Kata road");
        assertThat(first.visibility()).isEqualTo(SearchVisibility.PUBLIC);
    }

    @Test
    void petitionBackfill_neverIndexesADraft_andExcludesItFromCount() {
        // The no-leak fence: a DRAFT must never be discoverable. (The status query excludes drafts in production;
        // this asserts the SHARED fence still removes one if it ever reached the adapter — belt-and-braces.)
        Petition draft = Petition.create("Draft ask", "body", PetitionTargetType.OFFICE,
                UUID.randomUUID(), 50, null, UUID.randomUUID(), null); // stays DRAFT
        ReflectionTestUtils.setField(draft, "publicId", UUID.randomUUID());
        assertThat(draft.getStatus()).isEqualTo(PetitionStatus.DRAFT);
        Petition active = activePetition("Public ask", "body");
        when(petitions.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(draft, active), PageRequest.of(0, 200), 2));

        long upserted = petitionBackfill.backfill(searchIndex);

        assertThat(upserted).isEqualTo(1L); // only the active petition
        verify(searchIndex, times(1)).upsert(any());
        verify(searchIndex).remove(SearchEntityType.PETITION, draft.getPublicId());
    }

    @Test
    void petitionBackfill_isBatched_walksEveryPage() {
        List<Petition> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(activePetition("p" + i, "b" + i));
        }
        when(petitions.findByStatusIn(anyList(), any(Pageable.class)))
                .thenAnswer(inv -> slice(all, ((Pageable) inv.getArgument(1)).getPageNumber(), 2));

        long upserted = petitionBackfill.backfill(searchIndex);

        assertThat(upserted).isEqualTo(5L);
        verify(searchIndex, times(5)).upsert(any());
        verify(petitions, times(3)).findByStatusIn(anyList(), any(Pageable.class)); // pages 0,1,2
    }

    @Test
    void petitionBackfill_emptySource_returnsZero_neverThrows_neverIndexes() {
        when(petitions.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        long upserted = petitionBackfill.backfill(searchIndex);

        assertThat(upserted).isZero();
        verify(searchIndex, never()).upsert(any());
        verify(searchIndex, never()).remove(any(), any());
    }

    // ----- POLL (survey) -----

    @Test
    void surveyBackfill_upsertsPublicSurveys_underPoll_neverQuestionsJson() {
        Survey open = openSurvey("Maoni ya Maji", "Tafadhali toa maoni.", "[{\"prompt\":\"secret question\"}]");
        when(surveys.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(open), PageRequest.of(0, 200), 1));

        long upserted = surveyBackfill.backfill(searchIndex);

        assertThat(upserted).isEqualTo(1L);
        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(captor.capture());
        SearchDocumentUpsert pushed = captor.getValue();
        assertThat(pushed.entityType()).isEqualTo(SearchEntityType.POLL);
        assertThat(pushed.title()).isEqualTo("Maoni ya Maji");
        assertThat(pushed.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        // The questions JSON must never leak into any indexed field (PRD §18).
        assertThat(pushed.snippetSw()).doesNotContain("secret question");
        assertThat(pushed.snippetEn()).doesNotContain("secret question");
        assertThat(pushed.keywords()).doesNotContain("secret question");
    }

    @Test
    void surveyBackfill_neverIndexesADraft() {
        Survey draft = Survey.create("Draft poll", "desc", SurveyType.POLL, true,
                null, "[{\"prompt\":\"x\"}]", null, null, false, UUID.randomUUID(), null); // stays DRAFT
        assertThat(draft.getStatus()).isEqualTo(SurveyStatus.DRAFT);
        when(surveys.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(draft), PageRequest.of(0, 200), 1));

        long upserted = surveyBackfill.backfill(searchIndex);

        assertThat(upserted).isZero();
        verify(searchIndex, never()).upsert(any());
        verify(searchIndex).remove(SearchEntityType.POLL, draft.getPublicId());
    }

    // ----- QUESTION -----

    @Test
    void questionBackfill_upsertsPublicQuestions_neverTheAskerId() {
        UUID asker = UUID.randomUUID();
        UUID targetRep = UUID.randomUUID();
        Question open = Question.ask(asker, targetRep, "When will the clinic open?");
        when(questions.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(open), PageRequest.of(0, 200), 1));

        long upserted = questionBackfill.backfill(searchIndex);

        assertThat(upserted).isEqualTo(1L);
        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(captor.capture());
        SearchDocumentUpsert pushed = captor.getValue();
        assertThat(pushed.entityType()).isEqualTo(SearchEntityType.QUESTION);
        assertThat(pushed.title()).isEqualTo("When will the clinic open?");
        assertThat(pushed.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        // The target rep is the discoverability facet; the asker is NEVER surfaced (PRD §18).
        assertThat(pushed.areaId()).isEqualTo(targetRep);
        assertThat(pushed.authoredByAccountId()).isNull();
    }

    @Test
    void questionBackfill_emptySource_returnsZero_neverIndexes() {
        when(questions.findByStatusIn(anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        long upserted = questionBackfill.backfill(searchIndex);

        assertThat(upserted).isZero();
        verify(searchIndex, never()).upsert(any());
        verify(searchIndex, never()).remove(any(), any());
    }

    // ----- helpers -----

    /** Builds an ACTIVE (publicly visible) petition with a public id, mirroring the create→activate path. */
    private static Petition activePetition(String title, String body) {
        Petition p = Petition.create(title, body, PetitionTargetType.OFFICE, UUID.randomUUID(),
                100, null, UUID.randomUUID(), null);
        p.activate(); // DRAFT -> ACTIVE (publicly visible)
        ReflectionTestUtils.setField(p, "publicId", UUID.randomUUID()); // the create factory does not set it
        return p;
    }

    /** Builds an OPEN (publicly visible) survey with a public id. */
    private static Survey openSurvey(String title, String description, String questionsJson) {
        Survey s = Survey.create(title, description, SurveyType.SURVEY, false,
                null, questionsJson, null, null, false, UUID.randomUUID(), null);
        s.open(); // DRAFT -> OPEN
        ReflectionTestUtils.setField(s, "publicId", UUID.randomUUID());
        return s;
    }

    /** Builds the page at {@code pageNumber} of {@code size} over {@code all} (for the batching walk test). */
    private static Page<Petition> slice(List<Petition> all, int pageNumber, int size) {
        int from = Math.min(pageNumber * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageImpl<>(all.subList(from, to), PageRequest.of(pageNumber, size), all.size());
    }
}
