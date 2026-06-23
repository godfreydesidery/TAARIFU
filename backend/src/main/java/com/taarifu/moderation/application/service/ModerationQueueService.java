package com.taarifu.moderation.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.moderation.api.dto.ModerationActionDto;
import com.taarifu.moderation.api.dto.ModerationItemDto;
import com.taarifu.moderation.api.dto.TakeActionRequest;
import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.domain.model.ModerationAction;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.FlagStatus;
import com.taarifu.moderation.domain.model.enums.ModerationActionType;
import com.taarifu.moderation.domain.model.enums.ModerationItemStatus;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationActionRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Use-case service for the moderator queue: list items and take an append-only action (PRD §18, US-12.2,
 * UC-H01/H02, §25.8).
 *
 * <p>Responsibility: owns the "review the queue" and "take action" transactions. The integrity-critical
 * piece is the <b>conflict-of-interest guard (D16)</b>: a moderator may not action a queue item whose
 * subject they authored. It is enforced here via {@link ScopeGuard#isNotSelf(UUID)} against the item's
 * recorded {@code subjectAuthorProfileId}, throws {@link ErrorCode#CONFLICT_OF_INTEREST}, and writes an
 * {@link AuditEventType#AUTHZ_SELF_ACTION_BLOCKED} audit event — the regression test fails if this guard
 * is removed.</p>
 *
 * <p>Taking an action: appends an immutable {@link ModerationAction}, closes the item to its terminal
 * status ({@code APPROVE}→DISMISSED, everything else→ACTIONED), and resolves the subject's open flags so
 * each flagger gets feedback (US-12.1). The action row is never updated/deleted — a later reversal is a
 * new action, and the V41 grant enforces append-only at the database (§18, §25.8).</p>
 */
@Service
public class ModerationQueueService {

    private final ModerationItemRepository itemRepository;
    private final ModerationActionRepository actionRepository;
    private final FlagRepository flagRepository;
    private final ScopeGuard scopeGuard;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param itemRepository   queue store.
     * @param actionRepository append-only action log.
     * @param flagRepository   flag store (to close flags on resolution).
     * @param scopeGuard       {@code @taarifuAuthz} — supplies the D16 {@code isNotSelf} conflict check.
     * @param audit            append-only audit writer (denial + decision evidence).
     * @param clock            time source (testable).
     */
    public ModerationQueueService(ModerationItemRepository itemRepository,
                                  ModerationActionRepository actionRepository,
                                  FlagRepository flagRepository,
                                  ScopeGuard scopeGuard,
                                  AuditEventService audit,
                                  ClockPort clock) {
        this.itemRepository = itemRepository;
        this.actionRepository = actionRepository;
        this.flagRepository = flagRepository;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Lists the queue filtered by status (UC-H01).
     *
     * @param status   the queue status (e.g. {@code PENDING}).
     * @param pageable page + sort (e.g. {@code severity,desc}).
     * @return the paged queue read models.
     */
    @Transactional(readOnly = true)
    public Page<ModerationItemDto> queue(ModerationItemStatus status, Pageable pageable) {
        return itemRepository.findByStatus(status, pageable).map(ModerationItemDto::from);
    }

    /**
     * Takes a moderation action on a queue item (UC-H02), enforcing the D16 self-action guard.
     *
     * @param moderatorPublicId the acting moderator's public id (from the security context).
     * @param itemPublicId      the queue item to action.
     * @param request           the validated action request.
     * @return the recorded {@link ModerationActionDto} (its public id lets the moderator track an appeal).
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if the item is unknown,
     *                      {@link ErrorCode#CONFLICT} if it is already terminal,
     *                      {@link ErrorCode#CONFLICT_OF_INTEREST} if the moderator authored the subject (D16).
     */
    @Transactional
    public ModerationActionDto takeAction(UUID moderatorPublicId, UUID itemPublicId,
                                          TakeActionRequest request) {
        ModerationItem item = itemRepository.findByPublicId(itemPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        if (item.isTerminal()) {
            // Idempotency/state guard: a closed item cannot be re-actioned (a reversal is an appeal).
            throw new ApiException(ErrorCode.CONFLICT);
        }

        // D16 CONFLICT-OF-INTEREST: a moderator may not moderate their own content. The author is a
        // cross-module reference recorded on the item; if it is the moderator, block + audit. When the
        // author is unknown (null), there is nothing to conflict with (isNotSelf treats it as not-self).
        UUID authorProfileId = item.getSubjectAuthorProfileId();
        if (authorProfileId != null && !scopeGuard.isNotSelf(authorProfileId)) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTHZ_SELF_ACTION_BLOCKED, AuditOutcome.DENIED)
                    .actor(moderatorPublicId)
                    .subject(authorProfileId)
                    .reason("moderate_own_content")
                    .build());
            throw new ApiException(ErrorCode.CONFLICT_OF_INTEREST);
        }

        ModerationAction action = ModerationAction.record(item, request.type(), moderatorPublicId,
                authorProfileId, request.reasonCode(), request.note(), clock.now());
        ModerationAction saved = actionRepository.save(action);

        // Close the item: APPROVE = dismissed (no violation); any sanctioning action = actioned.
        ModerationItemStatus terminal = request.type() == ModerationActionType.APPROVE
                ? ModerationItemStatus.DISMISSED
                : ModerationItemStatus.ACTIONED;
        item.claim(moderatorPublicId);
        item.close(terminal, clock.now());
        itemRepository.save(item);

        closeFlags(item, terminal == ModerationItemStatus.ACTIONED
                ? FlagStatus.RESOLVED : FlagStatus.DISMISSED);

        // The append-only ModerationAction row IS the immutable decision trail (multi-hat context: the
        // acting moderator is on the row). A dedicated AuditEventType.MODERATION_ACTION_TAKEN does not yet
        // exist in the common catalogue (out of this module's edit scope — see CENTRAL INTEGRATION NEEDS),
        // so a success audit event is intentionally NOT written with a wrong type. The integrity-critical
        // DENIAL path above already uses the precise AUTHZ_SELF_ACTION_BLOCKED type.
        // TODO(wiring): emit `moderation_action_taken` {host_type, host_ref, action, action_latency_s} via
        // the transactional outbox; and, for SUSPEND/VERIFY_REQUEST, an identity-module sanction event —
        // never by reaching into identity from here (ARCHITECTURE.md §3.2, §8).
        return ModerationActionDto.from(saved);
    }

    /**
     * Resolves every live flag on a subject to its terminal status so flaggers get feedback (US-12.1).
     */
    private void closeFlags(ModerationItem item, FlagStatus terminal) {
        List<Flag> flags = flagRepository
                .findBySubjectTypeAndSubjectId(item.getSubjectType(), item.getSubjectId());
        for (Flag f : flags) {
            if (f.getStatus() == FlagStatus.OPEN) {
                f.close(terminal);
            }
        }
        flagRepository.saveAll(flags);
    }
}
