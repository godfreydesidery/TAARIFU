package com.taarifu.engagement.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Survey;
import com.taarifu.engagement.domain.model.SurveyResponse;
import com.taarifu.engagement.domain.model.enums.SurveyType;
import com.taarifu.engagement.domain.repository.SurveyRepository;
import com.taarifu.engagement.domain.repository.SurveyResponseRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurveyService} — proving the one-response-per-person pre-check, the open-window
 * rule, and that a binding poll's response path has <b>no token collaborator</b> (the fence by
 * construction, PRD §23.5) (CLAUDE.md §10).
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceTest {

    @Mock
    private SurveyRepository surveys;
    @Mock
    private SurveyResponseRepository responses;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private SearchIndexApi searchIndex;

    private final EngagementMapper mapper = new EngagementMapper();
    private SurveyService service;

    private final UUID responder = UUID.randomUUID();
    private final UUID surveyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // No token collaborator is injectable here — SurveyService cannot read a balance (integrity fence).
        // The OutboxWriter mock receives the survey_responded analytics fact (a passive side-record, not a gate).
        service = new SurveyService(surveys, responses, mapper, outboxWriter, searchIndex);
    }

    private Survey openBindingPoll() {
        Survey s = Survey.create("Poll", null, SurveyType.POLL, true,
                null, null, null, null, false, UUID.randomUUID(), null);
        s.open();
        return s;
    }

    @Test
    void respondHappyPath_savesResponse_forOpenSurvey() {
        Survey s = openBindingPoll();
        when(surveys.findByPublicId(surveyId)).thenReturn(Optional.of(s));
        when(responses.existsBySurvey_PublicIdAndResponderProfileId(surveyId, responder)).thenReturn(false);

        var dto = service.respond(surveyId, responder, "[{\"q\":0,\"a\":\"yes\"}]");

        assertThat(dto.binding()).isTrue();
        verify(responses).save(any(SurveyResponse.class));
    }

    @Test
    void respondRejectsDuplicate_onePerPerson() {
        Survey s = openBindingPoll();
        when(surveys.findByPublicId(surveyId)).thenReturn(Optional.of(s));
        when(responses.existsBySurvey_PublicIdAndResponderProfileId(surveyId, responder)).thenReturn(true);

        assertThatThrownBy(() -> service.respond(surveyId, responder, "[]"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(responses, never()).save(any());
    }

    @Test
    void respondRejectsClosedSurvey() {
        // A DRAFT/non-OPEN survey does not accept responses.
        Survey draft = Survey.create("S", null, SurveyType.SURVEY, false,
                null, null, null, null, false, UUID.randomUUID(), null);
        when(surveys.findByPublicId(surveyId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.respond(surveyId, responder, "[]"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(responses, never()).save(any());
    }

    @Test
    void createRejectsUnknownType() {
        assertThatThrownBy(() -> service.create("t", null, "QUIZ", false,
                null, null, null, null, false, responder))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createDraft_removesFromDiscovery_neverIndexesADraft() {
        // The no-leak fence: a freshly-created survey is DRAFT and must NOT be discoverable. The create path
        // routes through reindexForDiscovery, whose non-public branch REMOVES (idempotent) — never upserts.
        service.create("Poll title", "Desc", "POLL", true,
                null, "[{\"prompt\":\"x\"}]", null, null, false, responder);

        verify(searchIndex, never()).upsert(any());
        verify(searchIndex).remove(eq(SearchEntityType.POLL), any());
    }

    @Test
    void open_indexesPublicProjection_neverQuestionsJsonOrResponses() {
        // DRAFT -> OPEN makes the survey public-safe → it is upserted (under POLL for both SurveyTypes). Only
        // the title + description snippet are indexed; the questions JSON and any response payload never are.
        Survey draft = Survey.create("Maoni ya Maji", "Tafadhali toa maoni.", SurveyType.SURVEY, false,
                null, "[{\"prompt\":\"secret question\"}]", null, null, false, responder, null);
        when(surveys.findByPublicId(surveyId)).thenReturn(Optional.of(draft));

        service.open(surveyId);

        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(captor.capture());
        SearchDocumentUpsert pushed = captor.getValue();
        assertThat(pushed.entityType()).isEqualTo(SearchEntityType.POLL);
        assertThat(pushed.title()).isEqualTo("Maoni ya Maji");
        assertThat(pushed.snippetSw()).isEqualTo("Tafadhali toa maoni.");
        assertThat(pushed.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        // The questions JSON must never leak into any indexed field.
        assertThat(pushed.snippetSw()).doesNotContain("secret question");
        assertThat(pushed.snippetEn()).doesNotContain("secret question");
        assertThat(pushed.keywords()).doesNotContain("secret question");
    }
}
