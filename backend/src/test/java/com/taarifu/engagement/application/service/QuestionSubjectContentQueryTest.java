package com.taarifu.engagement.application.service;

import com.taarifu.engagement.domain.model.Answer;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.repository.AnswerRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuestionSubjectContentQuery} — engagement's content port that lets moderation's
 * auto-assist screen score a flagged Q&amp;A question (ADR-0018; ADR-0013 §4c; CLAUDE.md §10).
 *
 * <p>Responsibility: proves (a) the port declares {@link FlagSubjectType#QUESTION}; (b) an unanswered
 * question is scored on its body alone; (c) an answered question's scored document includes the rep's
 * published answer (the whole public thread is moderatable); (d) a missing question resolves to empty —
 * screen skipped, item still goes to a human (EI-18); and (e) no PII (asker id) is in the scored text.</p>
 */
@ExtendWith(MockitoExtension.class)
class QuestionSubjectContentQueryTest {

    @Mock
    private QuestionRepository questions;
    @Mock
    private AnswerRepository answers;

    private QuestionSubjectContentQuery query;

    private final UUID questionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        query = new QuestionSubjectContentQuery(questions, answers);
    }

    @Test
    void declaresQuestionSubjectType() {
        assertThat(query.subjectType()).isEqualTo(FlagSubjectType.QUESTION);
    }

    @Test
    void unansweredQuestion_scoresBodyOnly_noPii() {
        UUID asker = UUID.randomUUID();
        Question question = Question.ask(asker, UUID.randomUUID(), "Lini zahanati itafunguliwa?");
        when(questions.findByPublicId(questionId)).thenReturn(Optional.of(question));
        when(answers.findByQuestion_PublicId(questionId)).thenReturn(Optional.empty());

        Optional<String> text = query.contentTextOf(questionId);

        assertThat(text).isPresent();
        assertThat(text.get()).contains("Lini zahanati itafunguliwa?");
        // 🔒 No PII: the asker account id is never in the scored text (data minimisation, PRD §18).
        assertThat(text.get()).doesNotContain(asker.toString());
    }

    @Test
    void answeredQuestion_scoresBodyAndPublishedAnswer() {
        Question question = Question.ask(UUID.randomUUID(), UUID.randomUUID(), "Lini barabara itatengenezwa?");
        Answer answer = Answer.of(question, UUID.randomUUID(), "Itatengenezwa mwezi ujao.");
        when(questions.findByPublicId(questionId)).thenReturn(Optional.of(question));
        when(answers.findByQuestion_PublicId(questionId)).thenReturn(Optional.of(answer));

        Optional<String> text = query.contentTextOf(questionId);

        assertThat(text).isPresent();
        assertThat(text.get()).contains("Lini barabara itatengenezwa?"); // question scored
        assertThat(text.get()).contains("Itatengenezwa mwezi ujao.");     // rep's answer scored too
    }

    @Test
    void missingQuestion_resolvesEmpty_screenSkipped() {
        // EI-18: no question → empty → screen skipped; the flagged item still goes to a human moderator.
        when(questions.findByPublicId(questionId)).thenReturn(Optional.empty());
        // The answer lookup is short-circuited by the empty question (Optional.map), so it is lenient.
        lenient().when(answers.findByQuestion_PublicId(questionId)).thenReturn(Optional.empty());

        assertThat(query.contentTextOf(questionId)).isEmpty();
    }
}
