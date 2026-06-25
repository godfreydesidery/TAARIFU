package com.taarifu.engagement.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.repository.AnswerRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuestionService} — proving the no-self-question conflict guard (D16) and that a
 * valid ask is persisted as {@code OPEN} (CLAUDE.md §10).
 */
@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    @Mock
    private QuestionRepository questions;
    @Mock
    private AnswerRepository answers;
    @Mock
    private ScopeGuard scopeGuard;
    @Mock
    private AuditEventService audit;
    @Mock
    private SearchIndexApi searchIndex;

    private final EngagementMapper mapper = new EngagementMapper();
    private QuestionService service;

    private final UUID asker = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new QuestionService(questions, answers, mapper, scopeGuard, audit, searchIndex);
    }

    @Test
    void askBlocksSelfTargetedQuestion_andAudits() {
        UUID targetRep = UUID.randomUUID();
        when(scopeGuard.isNotSelf(targetRep)).thenReturn(false); // target IS the caller => self-action

        assertThatThrownBy(() -> service.ask(asker, targetRep, "Why?"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(questions, never()).save(any());
        verify(audit).record(any());
    }

    @Test
    void askPersistsOpenQuestion_forValidTarget() {
        UUID targetRep = UUID.randomUUID();
        when(scopeGuard.isNotSelf(targetRep)).thenReturn(true);

        var dto = service.ask(asker, targetRep, "When will the clinic open?");

        assertThat(dto.status()).isEqualTo("OPEN");
        assertThat(dto.askerProfileId()).isEqualTo(asker);
        assertThat(dto.targetRepId()).isEqualTo(targetRep);
        verify(questions).save(any(Question.class));
    }

    @Test
    void askIndexesPublicQuestion_forDiscovery_neverTheAskerId() {
        // SEARCH (ADR-0017 §1): a new OPEN question is publicly visible → it is upserted as a PUBLIC projection.
        // The target rep id is carried as the area facet (for "questions to this rep"); the asker id is NEVER
        // indexed (authoredByAccountId is null). This assertion FAILS if reindexForDiscovery is removed from ask().
        UUID targetRep = UUID.randomUUID();
        when(scopeGuard.isNotSelf(targetRep)).thenReturn(true);

        service.ask(asker, targetRep, "When will the clinic open?");

        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(captor.capture());
        verify(searchIndex, never()).remove(any(), any());
        SearchDocumentUpsert pushed = captor.getValue();
        assertThat(pushed.entityType()).isEqualTo(SearchEntityType.QUESTION);
        assertThat(pushed.title()).isEqualTo("When will the clinic open?");
        assertThat(pushed.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        // The target rep is the discoverability facet; the asker is never surfaced.
        assertThat(pushed.areaId()).isEqualTo(targetRep);
        assertThat(pushed.authoredByAccountId()).isNull();
    }

    @Test
    void markAnswered_keepsPublicProjectionFresh() {
        // An ANSWERED question stays public → it is re-upserted (idempotent) through the same single fence.
        UUID targetRep = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        Question q = Question.ask(asker, targetRep, "Why the delay?");
        when(questions.findByPublicId(questionId)).thenReturn(java.util.Optional.of(q));

        service.markAnswered(questionId);

        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex).upsert(captor.capture());
        assertThat(captor.getValue().entityType()).isEqualTo(SearchEntityType.QUESTION);
        assertThat(captor.getValue().keywords()).contains("ANSWERED");
    }
}
