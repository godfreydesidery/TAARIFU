package com.taarifu.engagement.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.engagement.api.dto.QuestionDto;
import com.taarifu.engagement.application.mapper.EngagementMapper;
import com.taarifu.engagement.domain.model.Answer;
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.enums.QuestionStatus;
import com.taarifu.engagement.domain.repository.AnswerRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application service for public Q&A to representatives — ask, list, view (PRD §12.2 M10, D13/D16).
 *
 * <p>Responsibility: owns the transaction boundary for questions. Asking is a <b>T2</b> action (PRD §7.3
 * "ask Q&A"); the tier gate is the controller's {@code @RequiresTier("T2")}. This service enforces the
 * <b>no-self-question</b> conflict guard (a representative may not ask a question targeting themselves —
 * D16) via the shared {@link ScopeGuard} seam, audited. Asking is <i>not</i> a binding act, so it is not
 * one-per-person and token balance is irrelevant.</p>
 */
@Service
@Transactional
public class QuestionService {

    /** Question statuses that are publicly visible (OPEN/ANSWERED — PRD §22.6). */
    private static final List<QuestionStatus> PUBLIC_STATUSES =
            List.of(QuestionStatus.OPEN, QuestionStatus.ANSWERED);

    /**
     * Max characters of the question body carried in the public discovery snippet — a lean public preview
     * only (PRD §15 data budget; well under ADR-0017's {@code snippet_*} 1024-char column).
     */
    private static final int SEARCH_SNIPPET_MAX = 480;

    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final EngagementMapper mapper;
    private final ScopeGuard scopeGuard;
    private final AuditEventService audit;
    private final SearchIndexApi searchIndex;

    /**
     * @param questions   question persistence port.
     * @param answers     answer persistence port (for the question-detail read).
     * @param mapper      entity→DTO mapper.
     * @param scopeGuard  conflict-of-interest seam ({@code @taarifuAuthz}) — no-self-question (D16).
     * @param audit       append-only audit writer (self-action denial evidence, L-1).
     * @param searchIndex the search module's published inbound port (ADR-0017 §1, ADR-0013 §1). This service
     *                    <b>pushes</b> a public, PII-free projection of a publicly-visible (OPEN/ANSWERED)
     *                    question into the discovery index on ask/answer and <b>removes</b> it when the
     *                    question is not (or no longer) public (DECLINED/MODERATED) — owner→search, an
     *                    {@code api → api} call, never a reach-in. The asker id is NEVER indexed.
     */
    public QuestionService(QuestionRepository questions,
                           AnswerRepository answers,
                           EngagementMapper mapper,
                           ScopeGuard scopeGuard,
                           AuditEventService audit,
                           SearchIndexApi searchIndex) {
        this.questions = questions;
        this.answers = answers;
        this.mapper = mapper;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.searchIndex = searchIndex;
    }

    /**
     * Lists publicly-visible questions (OPEN/ANSWERED), paged. Optionally filtered to a target rep.
     *
     * @param targetRepId optional representative public id to filter by (the rep's Q&A inbox), or {@code null}.
     * @param pageable    bounded paging/sorting.
     * @return a page of {@link QuestionDto} (without answer bodies in the list view — answers are fetched on detail).
     */
    @Transactional(readOnly = true)
    public Page<QuestionDto> listPublic(UUID targetRepId, Pageable pageable) {
        Page<Question> page = targetRepId == null
                ? questions.findByStatusIn(PUBLIC_STATUSES, pageable)
                : questions.findByTargetRepIdAndStatusIn(targetRepId, PUBLIC_STATUSES, pageable);
        // List view omits the answer body (kept lean for feature phones — PRD §15); detail carries it.
        return page.map(q -> mapper.toQuestionDto(q, null));
    }

    /**
     * Fetches a single question by public id, including its answer when present.
     *
     * @param publicId the question's public id.
     * @return the {@link QuestionDto} with answer fields populated if answered.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    @Transactional(readOnly = true)
    public QuestionDto get(UUID publicId) {
        Question question = require(publicId);
        var answer = answers.findByQuestion_PublicId(publicId).orElse(null);
        return mapper.toQuestionDto(question, answer);
    }

    /**
     * Asks a representative a public question (UC-E09) on behalf of the authenticated asker (T2).
     *
     * @param askerPublicId the authenticated asker's account public id (from {@code CurrentUser}, never the body).
     * @param targetRepId   the targeted representative's public id (institutions module; by id only).
     * @param body          the question text.
     * @return the created {@link QuestionDto} (status {@code OPEN}).
     * @throws ApiException {@link ErrorCode#CONFLICT_OF_INTEREST} if the asker targets themselves (a rep may
     *                      not ask a question about their own representative record — D16).
     */
    public QuestionDto ask(UUID askerPublicId, UUID targetRepId, String body) {
        // D16: a representative may not ask a question targeting themselves.
        if (!scopeGuard.isNotSelf(targetRepId)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(askerPublicId)
                    .subject(targetRepId)
                    .reason("QUESTION_AGAINST_SELF")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        // TODO(wiring): resolve askerPublicId (account) -> identity Profile public id, and validate
        // targetRepId against the institutions registry, once those modules are wired.
        Question question = Question.ask(askerPublicId, targetRepId, body);
        questions.save(question);
        // SEARCH (ADR-0017 §1, ADR-0013 §1): a new question is OPEN — publicly visible — so push its public,
        // PII-free projection into discovery. reindexForDiscovery decides upsert-vs-remove from the question's
        // own visibility (the single no-leak fence), so a future DECLINED/MODERATED state self-heals out of
        // discovery through the same helper. The asker id is NEVER indexed (PRD §18).
        reindexForDiscovery(question);
        return mapper.toQuestionDto(question, null);
    }

    /**
     * Publishes a representative's answer to an {@code OPEN} question (UC-E10, US-10.2) — records the
     * {@link Answer} and flips the question to {@code ANSWERED} in one transaction, then refreshes the
     * discovery projection through the single {@link #reindexForDiscovery(Question)} fence (an ANSWERED
     * question stays publicly visible).
     *
     * <p><b>Authorization / integrity (D13/D16).</b> Only the <b>targeted</b> representative may answer their
     * own Q&amp;A inbox: the answering principal (taken from {@code CurrentUser}, never the body) must equal the
     * question's {@code targetRepId}. A caller who is not the target is {@link ErrorCode#FORBIDDEN}, audited —
     * the same boundary discipline as {@code ask} (the actor is the authenticated principal, referenced by
     * account public id; mapping account → institutions rep id is the documented cross-module wiring step,
     * // TODO(wiring)). One answer per question is guaranteed by the {@link Answer} unique constraint and the
     * {@code OPEN}-only {@link Question#markAnswered()} guard; a second answer is a clean
     * {@link ErrorCode#CONFLICT}. No token balance is read on this path — answering is a representative duty,
     * never a paid/weighted action.</p>
     *
     * <p>WHY this is the trigger for {@code markAnswered}: the wave-3 {@code markAnswered()} transition is only
     * coherent alongside a published answer (an ANSWERED question with no answer body would be a contradiction),
     * so answering is the single public path that reaches {@code ANSWERED} — it both persists the answer and
     * advances the lifecycle.</p>
     *
     * @param questionPublicId the question to answer.
     * @param answeringRepId   the answering representative's account public id (from {@code CurrentUser}); must
     *                         equal the question's {@code targetRepId}.
     * @param body             the answer text.
     * @return the now-ANSWERED {@link QuestionDto}, including the answer body.
     * @throws ResourceNotFoundException if the question does not exist.
     * @throws ApiException {@link ErrorCode#FORBIDDEN} if the answerer is not the targeted representative
     *                      (audited); {@link ErrorCode#CONFLICT} if the question is not {@code OPEN} / already
     *                      answered.
     */
    public QuestionDto answer(UUID questionPublicId, UUID answeringRepId, String body) {
        Question question = require(questionPublicId);

        // D13/D16: only the TARGETED representative may answer this question. The answerer is the authenticated
        // principal (not the body), so a caller can only answer questions addressed to them.
        if (!answeringRepId.equals(question.getTargetRepId())) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(answeringRepId)
                    .subject(question.getTargetRepId())
                    .reason("ANSWER_QUESTION_NOT_TARGET_REP")
                    .build());
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        Answer answer = Answer.of(question, answeringRepId, body);
        try {
            answers.save(answer);
        } catch (DataIntegrityViolationException dup) {
            // Concurrent double-answer hit uq_qa_answer_question — one answer per question held; clean conflict.
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }
        try {
            question.markAnswered();
        } catch (IllegalStateException notOpen) {
            // DECLINED / MODERATED / already ANSWERED: not answerable now — surface a clean conflict.
            throw new ApiException(ErrorCode.CONFLICT, notOpen);
        }
        // Still publicly visible (ANSWERED) → keep the discovery row fresh (ADR-0017 §1).
        reindexForDiscovery(question);
        return mapper.toQuestionDto(question, answer);
    }

    /** Loads a question by public id or throws a localised not-found. */
    private Question require(UUID publicId) {
        return questions.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("engagement.question.notFound", publicId));
    }

    /**
     * Pushes (or removes) this question's <b>public, PII-free</b> discovery projection in the search index
     * (ADR-0017 §1; ADR-0013 §1 owner→search). The single place the index-vs-no-index decision lives, so the
     * privacy fence is enforced once across the ask/answer call sites — mirroring reporting's pattern.
     *
     * <p><b>The fence (PRD §18, ADR-0017 §1/§4):</b> a question is indexed <b>only if</b> it is publicly
     * visible — {@code OPEN} or {@code ANSWERED} ({@link Question#isPubliclyVisible()}). A {@code DECLINED} or
     * {@code MODERATED} question is <b>never</b> discoverable, so it is positively {@link SearchIndexApi#remove
     * removed} (idempotent on an absent row).</p>
     *
     * <p><b>What is pushed (public-display + opaque ids only — never PII):</b> a {@link #snippet(String)
     * snippet} of the question body as BOTH the title and the preview (a Q&amp;A question has no separate
     * headline — the body is the public content; already public for an OPEN/ANSWERED question), and the
     * target representative public id as the <b>area facet</b> so a citizen can discover questions put to a
     * given rep. <b>Never</b> the asker id. {@code authoredByAccountId} is left {@code null}: the asker is the
     * one party we must never surface for a question, and the row carries no other author to maintain.</p>
     *
     * @param question the question whose discovery projection is being maintained.
     */
    private void reindexForDiscovery(Question question) {
        if (!question.isPubliclyVisible()) {
            // DECLINED / MODERATED (or any non-public state): ensure it is absent from discovery (idempotent).
            searchIndex.remove(SearchEntityType.QUESTION, question.getPublicId());
            return;
        }
        String body = snippet(question.getBody());
        searchIndex.upsert(new SearchDocumentUpsert(
                SearchEntityType.QUESTION,
                question.getPublicId(),
                // The body IS the public content of a Q&A question (no separate headline); use it as the label.
                body,
                body,
                body,
                // Keywords: the status as a searchable term (OPEN / ANSWERED) — public, non-PII.
                question.getStatus().name(),
                // areaId facet reused to carry the TARGET REP id so "questions to this rep" is discoverable;
                // it is a public institutions-module id, never PII.
                question.getTargetRepId(),
                null,                                  // categoryId: n/a for a question
                SearchVisibility.PUBLIC,
                // authoredByAccountId: deliberately null — the asker must never be surfaced for a question.
                null));
    }

    /**
     * Truncates citizen free text to a lean, index-safe discovery snippet ({@link #SEARCH_SNIPPET_MAX} chars),
     * appending an ellipsis when cut. Keeps the index payload lean (PRD §15) and under the index column bound.
     *
     * @param text the source text (may be {@code null}).
     * @return the trimmed snippet, or {@code null} if the input is {@code null}/blank.
     */
    private String snippet(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() <= SEARCH_SNIPPET_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, SEARCH_SNIPPET_MAX) + "…";
    }
}
