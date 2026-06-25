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
import com.taarifu.engagement.domain.model.Question;
import com.taarifu.engagement.domain.model.enums.QuestionStatus;
import com.taarifu.engagement.domain.repository.AnswerRepository;
import com.taarifu.engagement.domain.repository.QuestionRepository;
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

    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final EngagementMapper mapper;
    private final ScopeGuard scopeGuard;
    private final AuditEventService audit;

    /**
     * @param questions  question persistence port.
     * @param answers    answer persistence port (for the question-detail read).
     * @param mapper     entity→DTO mapper.
     * @param scopeGuard conflict-of-interest seam ({@code @taarifuAuthz}) — no-self-question (D16).
     * @param audit      append-only audit writer (self-action denial evidence, L-1).
     */
    public QuestionService(QuestionRepository questions,
                           AnswerRepository answers,
                           EngagementMapper mapper,
                           ScopeGuard scopeGuard,
                           AuditEventService audit) {
        this.questions = questions;
        this.answers = answers;
        this.mapper = mapper;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
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
        // TODO(wiring/search, ADR-0017 §1): a public (OPEN/ANSWERED) question is discoverable — push a PUBLIC
        // projection to search.api.SearchIndexApi.upsert (lean body summary as title/snippet); on the answer
        // flow re-upsert, and on delete/hide call remove(...). BLOCKED: SearchEntityType has no QUESTION value
        // (search-owner CENTRAL NEED — see package-info). No PII (never the asker id) is indexed.
        return mapper.toQuestionDto(question, null);
    }

    /** Loads a question by public id or throws a localised not-found. */
    private Question require(UUID publicId) {
        return questions.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("engagement.question.notFound", publicId));
    }
}
