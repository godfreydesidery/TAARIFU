package com.taarifu.engagement.application.service;

import com.taarifu.engagement.domain.repository.AnswerRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.SubjectContentQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Engagement's implementation of the moderation {@link SubjectContentQueryApi} for
 * {@link FlagSubjectType#QUESTION} subjects (ADR-0018; ADR-0013 §4c; US-12.3, UC-H05, D-Q8).
 *
 * <p>Responsibility: given a flagged Q&amp;A question's public id, return its <b>scorable text</b> so
 * moderation's auto-assist screen can run the {@code ContentSafety} scorer and <b>hold-and-prioritise</b> a
 * risky item for a human. It is the read-side twin of the question author lookup, registered by Spring into
 * moderation's {@code SubjectContentResolver} registry so moderation never imports engagement's internals
 * (dependency inversion — moderation owns the interface; no cycle, ARCHITECTURE §3.2).</p>
 *
 * <p>WHY both the question body <b>and the rep's answer</b> (when present) are scored: a flag against a Q&amp;A
 * thread may target either the citizen's question or the representative's published answer — both are public,
 * moderatable content under the same {@code (QUESTION, subjectId)} subject. Joining the question with its
 * answer (when answered) lets one screen cover the whole visible thread; the join is a newline so tokens do
 * not bleed across the two fields.</p>
 *
 * <p><b>🔒 Assist only, transient, no PII leak (D-Q8, R21, PRD §18, PDPA).</b> The returned text is the public
 * question/answer content only — never the asker's account id or any PII. Moderation hands it straight to the
 * scorer inside the triage transaction and never persists or logs it (see {@link SubjectContentQueryApi}); the
 * screen can only raise a queue item for a human (no takedown path). An absent/soft-deleted question resolves
 * to {@link Optional#empty()} — the screen is skipped and the flagged item still goes to a human (the EI-18
 * human-pipeline floor).</p>
 */
@Service
@Transactional(readOnly = true)
public class QuestionSubjectContentQuery implements SubjectContentQueryApi {

    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;

    /**
     * @param questionRepository question persistence port (content lookup by public id).
     * @param answerRepository   answer persistence port (the published answer is part of the moderatable thread).
     */
    public QuestionSubjectContentQuery(QuestionRepository questionRepository,
                                       AnswerRepository answerRepository) {
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
    }

    /** {@inheritDoc} */
    @Override
    public FlagSubjectType subjectType() {
        return FlagSubjectType.QUESTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the question body, with the representative's published answer appended when the question is
     * answered, joined as one scorable document. Empty for a non-existent / soft-deleted question (screen
     * skipped → the flagged item still reaches a human).</p>
     */
    @Override
    public Optional<String> contentTextOf(UUID subjectId) {
        return questionRepository.findByPublicId(subjectId)
                .map(question -> {
                    // Include the rep's published answer in the scored document when present — the whole public
                    // Q&A thread is the moderatable surface. Body + answer only; never the asker id or any PII.
                    String answerBody = answerRepository.findByQuestion_PublicId(subjectId)
                            .map(a -> "\n" + a.getBody())
                            .orElse("");
                    return question.getBody() + answerBody;
                });
    }
}
