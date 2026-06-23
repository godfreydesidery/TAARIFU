package com.taarifu.moderation.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.moderation.api.dto.FlagContentRequest;
import com.taarifu.moderation.api.dto.FlagDto;
import com.taarifu.moderation.domain.model.Flag;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.repository.FlagRepository;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use-case service for citizen flagging (PRD §18, US-12.1, UC-E13/H01).
 *
 * <p>Responsibility: owns the "flag content" transaction. It (a) blocks a citizen from flagging the same
 * subject twice (anti-brigading — the count that drives severity must reflect <i>distinct</i> flaggers),
 * (b) opens a {@link ModerationItem} for the subject if none is live yet (or attaches to / escalates the
 * existing one), seeding severity from the {@link SeverityPolicy}, and (c) records the {@link Flag}.</p>
 *
 * <p>WHY no token balance is read anywhere here (integrity fence, D18, §23.5): flagging is a civic-core
 * safety action open to any authenticated citizen (T1+); pricing or quota-gating it would silence
 * feature-phone users and is forbidden. Tier (T1+) is enforced declaratively on the controller, not by a
 * balance check.</p>
 *
 * <p>WHY the queue item's {@code subjectAuthorProfileId} is backfilled here from the owning module's
 * published lookup ({@link SubjectAuthorResolver} → {@code SubjectAuthorQueryApi}, ADR-0013 §4c): the D16
 * self-action guard on the action endpoint compares the moderator against the subject's author, so the
 * author must be on the item when it is opened. Moderation never imports the content owner — it dispatches
 * by {@code subjectType} to the owner's published port (ARCHITECTURE.md §3.2). A subject with no surfaced
 * author (e.g. an anonymous sensitive report — D-Q1) resolves to {@code null}, leaving the guard vacuously
 * satisfied (deny-by-default still applies to the moderator role itself).</p>
 */
@Service
public class FlagService {

    private final FlagRepository flagRepository;
    private final ModerationItemRepository itemRepository;
    private final SeverityPolicy severityPolicy;
    private final SubjectAuthorResolver subjectAuthorResolver;
    private final ClockPort clock;

    /**
     * @param flagRepository        flag store (dedup + persistence).
     * @param itemRepository        queue store (one-live-item-per-subject collapse).
     * @param severityPolicy        reason→severity classification (§25.8).
     * @param subjectAuthorResolver resolves the subject's author via the owning module's published port
     *                              (ADR-0013 §4c) so the D16 self-action guard has the author to compare.
     * @param clock                 time source for SLA stamping (testable).
     */
    public FlagService(FlagRepository flagRepository,
                       ModerationItemRepository itemRepository,
                       SeverityPolicy severityPolicy,
                       SubjectAuthorResolver subjectAuthorResolver,
                       ClockPort clock) {
        this.flagRepository = flagRepository;
        this.itemRepository = itemRepository;
        this.severityPolicy = severityPolicy;
        this.subjectAuthorResolver = subjectAuthorResolver;
        this.clock = clock;
    }

    /**
     * Flags content on behalf of the authenticated citizen.
     *
     * <p>WHY the grain is the <b>account</b> public id (R-2, D16): {@code flaggerAccountPublicId} carries the
     * caller's immutable <b>account</b> public id ({@code CurrentUser.requirePublicId()} = the JWT subject =
     * {@code app_user.publicId}), <b>not</b> a {@code Profile} id. The D16 self-action guard on the action
     * endpoint compares the moderator's account id against the subject's author (also an account id), so this
     * id must be the account grain end-to-end or the guard would silently mismatch. The persistence column is
     * still named {@code flagger_profile_id} for historical reasons — the value stored there is the account
     * public id (the grain contract, not the column name, is what matters for D16).</p>
     *
     * @param flaggerAccountPublicId the flagging citizen's <b>account</b> public id (from the security
     *                               context, never a body-supplied id).
     * @param request                the validated flag request.
     * @return the created {@link FlagDto} (so the flagger gets feedback — US-12.1).
     * @throws ApiException {@link ErrorCode#CONFLICT} if this citizen already flagged this subject.
     */
    @Transactional
    public FlagDto flag(UUID flaggerAccountPublicId, FlagContentRequest request) {
        // Anti-brigading: one live flag per (citizen, subject). The DB UNIQUE index is the hard backstop;
        // this pre-check returns a clean CONFLICT rather than a raw constraint violation on the common path.
        if (flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flaggerAccountPublicId, request.subjectType(), request.subjectId())) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        ModerationSeverity severity = severityPolicy.initialSeverity(request.reason());

        // Collapse: attach to the live queue item for this subject, or open a new one. On open, backfill the
        // subject's author (account public id) via the owning module's published port (ADR-0013 §4c) so the
        // D16 self-action guard on the action endpoint has the author to compare against. A subject with no
        // surfaced author (e.g. anonymous report) resolves to null — the guard is then vacuously satisfied.
        ModerationItem item = itemRepository
                .findBySubjectTypeAndSubjectId(request.subjectType(), request.subjectId())
                .orElseGet(() -> ModerationItem.open(request.subjectType(), request.subjectId(),
                        subjectAuthorResolver.authorOf(request.subjectType(), request.subjectId())
                                .orElse(null),
                        severity, clock.now()));
        item.recordFlag(severity, clock.now());
        ModerationItem savedItem = itemRepository.save(item);

        Flag flag = Flag.open(request.subjectType(), request.subjectId(), flaggerAccountPublicId,
                request.reason(), request.detail());
        flag.attachTo(savedItem.getPublicId());

        try {
            Flag saved = flagRepository.save(flag);
            // TODO(wiring): emit `content_flagged` {host_type, host_ref, flag_reason} via the transactional
            // outbox once the outbox/event bus is available (ARCHITECTURE.md §8; PRD Appendix M12 events).
            return FlagDto.from(saved);
        } catch (DataIntegrityViolationException dup) {
            // Lost the race against a concurrent identical flag — surface the same clean CONFLICT.
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }
    }
}
