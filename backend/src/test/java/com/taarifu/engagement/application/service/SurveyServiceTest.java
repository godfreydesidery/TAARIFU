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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private final EngagementMapper mapper = new EngagementMapper();
    private SurveyService service;

    private final UUID responder = UUID.randomUUID();
    private final UUID surveyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // No token collaborator is injectable here — SurveyService cannot read a balance (integrity fence).
        // The OutboxWriter mock receives the survey_responded analytics fact (a passive side-record, not a gate).
        service = new SurveyService(surveys, responses, mapper, outboxWriter);
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
}
