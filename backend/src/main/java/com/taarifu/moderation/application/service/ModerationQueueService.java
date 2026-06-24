package com.taarifu.moderation.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.moderation.api.dto.ModerationActionDto;
import com.taarifu.moderation.api.dto.ModerationItemDto;
import com.taarifu.moderation.api.dto.TakeActionRequest;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.api.event.ModerationSanctionApplied;
import com.taarifu.moderation.api.event.SanctionType;
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
    private final OutboxWriter outboxWriter;

    /**
     * @param itemRepository   queue store.
     * @param actionRepository append-only action log.
     * @param flagRepository   flag store (to close flags on resolution).
     * @param scopeGuard       {@code @taarifuAuthz} — supplies the D16 {@code isNotSelf} conflict check.
     * @param audit            append-only audit writer (denial + decision evidence).
     * @param clock            time source (testable).
     * @param outboxWriter     the transactional-outbox port; {@link #takeAction} appends a
     *                         {@code moderation_action_taken} analytics fact in the action transaction so the
     *                         analytics sink records it asynchronously, off the moderator's path (Appendix E, M15).
     */
    public ModerationQueueService(ModerationItemRepository itemRepository,
                                  ModerationActionRepository actionRepository,
                                  FlagRepository flagRepository,
                                  ScopeGuard scopeGuard,
                                  AuditEventService audit,
                                  ClockPort clock,
                                  OutboxWriter outboxWriter) {
        this.itemRepository = itemRepository;
        this.actionRepository = actionRepository;
        this.flagRepository = flagRepository;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.clock = clock;
        this.outboxWriter = outboxWriter;
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
        // acting moderator is on the row). Mirror the state-changing decision into the unified audit store
        // so every moderation action sits beside the DENIAL events (the conflict-of-interest path above
        // already records AUTHZ_SELF_ACTION_BLOCKED). actor=moderator, subject=content author (may be null
        // when the author is not surfaced); reason_code = the action taken. No content body/PII is attached.
        audit.record(AuditEvent.Builder
                .of(AuditEventType.MODERATION_ACTION_TAKEN, AuditOutcome.SUCCESS)
                .actor(moderatorPublicId)
                .subject(authorProfileId)
                .reason(request.type().name())
                .detailRef(item.getPublicId().toString())
                .build());
        // ANALYTICS (Appendix E, M15): emit a moderation_action_taken civic-activity fact on the outbox in
        // THIS transaction — the analytics sink records it ASYNCHRONOUSLY, off the moderator's path. The
        // action taken is carried as the controlled-vocabulary `outcome` code (e.g. APPROVE/HIDE/REMOVE); the
        // active role is MODERATOR. Ids/codes ONLY — NO content body, NO author identity, NO flag text
        // (PRD §18, PDPA, ADR-0014 §1). The aggregate is the moderation item's public id.
        outboxWriter.append(EventEnvelope.of(
                AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY,
                item.getPublicId(),
                new CivicActivityRecorded(
                        AnalyticsEventTypes.MODERATION_ACTION_TAKEN,
                        clock.now(),
                        null,                       // actorRef: no pseudonymous actor hash resolved here
                        null,                       // geoAreaId: moderation is not geo-scoped
                        null,                       // categoryId: n/a for a moderation action
                        null,                       // tier: n/a
                        null,                       // channel: n/a (server-side action)
                        "MODERATOR",                // activeRole name (string — NOT the analytics enum; ADR-0013 §3)
                        null,                       // latencySeconds: action latency is a later refinement
                        null,                       // breachType: n/a
                        request.type().name()),     // outcome = the moderation action taken (controlled vocab)
                clock.now()));

        // ACCOUNT SANCTION (A5; ADR-0013 §2, ADR-0014 §1): a SUSPEND/VERIFY_REQUEST action sanctions the
        // author's ACCOUNT. Moderation does NOT own account state and must NOT reach into identity
        // (ARCHITECTURE.md §3.2). Instead it appends a MODERATION_SANCTION_APPLIED event to the outbox in
        // THIS transaction; the identity module's handler consumes it asynchronously and applies the
        // account-state change (suspend / verify-request gate). Skipped when the author is not surfaced
        // (null) — there is no account to sanction. Payload = {subjectAccountId, sanctionType} only — ids/
        // enums, NO PII (PRD §18, PDPA). Idempotency key = the outbox public_id (the identity transition is
        // naturally idempotent — re-suspending an already-suspended account is a no-op, ADR-0014 §3).
        SanctionType sanction = sanctionFor(request.type());
        if (sanction != null && authorProfileId != null) {
            outboxWriter.append(EventEnvelope.of(
                    ModerationEventTypes.MODERATION_SANCTION_APPLIED,
                    ModerationEventTypes.AGGREGATE_MODERATION_ITEM,
                    item.getPublicId(),
                    new ModerationSanctionApplied(authorProfileId, sanction, clock.now()),
                    clock.now()));
        }
        return ModerationActionDto.from(saved);
    }

    /**
     * Maps a moderation action to the account sanction it implies, or {@code null} for content-only actions.
     *
     * <p>Only {@link ModerationActionType#SUSPEND} and {@link ModerationActionType#VERIFY_REQUEST} sanction
     * the <i>account</i>; APPROVE/HIDE/REMOVE/WARN act on the <i>content</i> and raise no sanction event
     * (the content-hide effect is a separate, also event-driven, takedown — ADR-0013 §2). Returning a
     * dedicated {@link SanctionType} (an {@code api.event} enum, not the domain {@code ModerationActionType})
     * keeps the cross-module contract to identity minimal and free of any moderation domain type.</p>
     *
     * @param type the action just recorded.
     * @return the implied {@link SanctionType}, or {@code null} if the action is content-only.
     */
    private static SanctionType sanctionFor(ModerationActionType type) {
        return switch (type) {
            case SUSPEND -> SanctionType.SUSPEND;
            case VERIFY_REQUEST -> SanctionType.VERIFY_REQUEST;
            case APPROVE, HIDE, REMOVE, WARN -> null;
        };
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
