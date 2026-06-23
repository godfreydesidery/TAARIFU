package com.taarifu.moderation.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.moderation.api.dto.AppealDto;
import com.taarifu.moderation.api.dto.DecideAppealRequest;
import com.taarifu.moderation.api.dto.FileAppealRequest;
import com.taarifu.moderation.domain.model.Appeal;
import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.model.enums.AppealStatus;
import com.taarifu.moderation.domain.repository.AppealRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use-case service for appeals against moderation actions (PRD §25.8, UC-H03).
 *
 * <p>Responsibility: owns the "file appeal" and "decide appeal" transactions and enforces the two
 * independence invariants §25.8 (Appendix F footnote ᵉ) demands:</p>
 * <ul>
 *   <li><b>Only the affected party may appeal</b>: the appellant must be the actioned content's author
 *       (the {@code subjectAuthorProfileId} frozen on the action). Otherwise
 *       {@link ErrorCode#FORBIDDEN}.</li>
 *   <li><b>Appeal independence (D16)</b>: an appeal is decided by a <b>different</b> moderator than the
 *       one who took the original action. A same-moderator decision is blocked with
 *       {@link ErrorCode#CONFLICT_OF_INTEREST} and audited
 *       ({@link AuditEventType#AUTHZ_SELF_ACTION_BLOCKED}) — the regression test fails if removed.</li>
 * </ul>
 *
 * <p>At most one live appeal per action is allowed (the {@code action_id} UNIQUE constraint is the DB
 * backstop; a clean {@link ErrorCode#CONFLICT} is returned on the common path). Deciding an appeal does
 * not mutate the original {@link ModerationAction} (append-only): an {@link AppealStatus#OVERTURNED}
 * outcome is the signal for a <i>new</i> reversing action, handled by wiring/ops, never by editing
 * history.</p>
 */
@Service
public class AppealService {

    private final AppealRepository appealRepository;
    private final ModerationActionRepository actionRepository;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param appealRepository appeal store (one-live-appeal-per-action).
     * @param actionRepository append-only action log (to resolve the appealed action).
     * @param audit            append-only audit writer (independence-denial evidence).
     * @param clock            time source (testable).
     */
    public AppealService(AppealRepository appealRepository,
                         ModerationActionRepository actionRepository,
                         AuditEventService audit,
                         ClockPort clock) {
        this.appealRepository = appealRepository;
        this.actionRepository = actionRepository;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Files an appeal against a moderation action (UC-H03), allowed only to the affected content author.
     *
     * <p>WHY the grain is the <b>account</b> public id (R-2, D16): {@code appellantAccountPublicId} carries
     * the caller's immutable <b>account</b> public id ({@code CurrentUser.requirePublicId()} = the JWT
     * subject), <b>not</b> a {@code Profile} id. The "only the affected author may appeal" check compares it
     * against {@code ModerationAction.subjectAuthorProfileId}, which is itself the author's account public id
     * (the same grain frozen when the queue item was opened). The two ids must be the same grain or a
     * legitimate author would be wrongly forbidden / an arbitrary account wrongly allowed — so the parameter
     * name now states the account grain. The persistence column keeps its historical {@code *_profile_id}
     * name; the value is the account id.</p>
     *
     * @param appellantAccountPublicId the appellant's <b>account</b> public id (from the security context,
     *                                 never a body-supplied id).
     * @param actionPublicId           the action being appealed.
     * @param request                  the validated appeal request.
     * @return the created {@link AppealDto}.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the action is unknown,
     *                      {@link ErrorCode#FORBIDDEN} if the appellant is not the affected author,
     *                      {@link ErrorCode#CONFLICT} if the action already has a live appeal.
     */
    @Transactional
    public AppealDto fileAppeal(UUID appellantAccountPublicId, UUID actionPublicId, FileAppealRequest request) {
        ModerationAction action = actionRepository.findByPublicId(actionPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        // Only the affected party may appeal. When the action has no surfaced author, no one is the
        // affected party here — deny-by-default (FORBIDDEN) rather than allow an arbitrary appellant.
        UUID affectedAuthor = action.getSubjectAuthorProfileId();
        if (affectedAuthor == null || !affectedAuthor.equals(appellantAccountPublicId)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }

        if (appealRepository.existsByActionPublicId(actionPublicId)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        Appeal appeal = Appeal.open(action, appellantAccountPublicId, request.grounds());
        try {
            Appeal saved = appealRepository.save(appeal);
            return AppealDto.from(saved);
        } catch (DataIntegrityViolationException dup) {
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }
    }

    /**
     * Decides an open appeal (UC-H03), enforcing appeal independence (D16, §25.8).
     *
     * @param moderatorPublicId the deciding moderator's public id (from the security context).
     * @param appealPublicId    the appeal to decide.
     * @param request           the validated decision request.
     * @return the decided {@link AppealDto}.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the appeal is unknown,
     *                      {@link ErrorCode#CONFLICT} if it is not OPEN (already decided),
     *                      {@link ErrorCode#CONFLICT_OF_INTEREST} if the decider took the original action.
     */
    @Transactional
    public AppealDto decideAppeal(UUID moderatorPublicId, UUID appealPublicId,
                                  DecideAppealRequest request) {
        Appeal appeal = appealRepository.findByPublicId(appealPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        if (appeal.getStatus() != AppealStatus.OPEN) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // APPEAL INDEPENDENCE (D16, §25.8 footnote ᵉ): the decider must NOT be the moderator who took the
        // original action. This is the load-bearing fairness guarantee — block + audit if violated.
        UUID originalModerator = appeal.getAction().getModeratorProfileId();
        if (moderatorPublicId.equals(originalModerator)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(moderatorPublicId)
                    .subject(appeal.getAppellantProfileId())
                    .reason("handle_own_action_appeal")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        // The entity's decide() additionally asserts the different-moderator invariant (defence-in-depth).
        appeal.decide(request.outcome(), moderatorPublicId, request.decisionNote(), clock.now());
        Appeal saved = appealRepository.save(appeal);

        // Mirror the resolved appeal into the unified audit store (beside the independence-denial event
        // above). actor=deciding moderator, subject=appellant; reason_code = the appeal outcome. An
        // OVERTURNED outcome is the signal for a new reversing action — history is never mutated (§25.8).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.MODERATION_APPEAL_RESOLVED, AuditOutcome.SUCCESS)
                .actor(moderatorPublicId)
                .subject(saved.getAppellantProfileId())
                .reason(request.outcome().name())
                .detailRef(saved.getPublicId().toString())
                .build());
        // TODO(wiring): also emit `moderation_appeal_resolved` {host_ref, outcome} via the transactional
        // outbox; an OVERTURNED outcome triggers a new reversing ModerationAction by ops/wiring.
        return AppealDto.from(saved);
    }
}
