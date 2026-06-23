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
 * <p>WHY the queue item's {@code subjectAuthorProfileId} is left {@code null} here: resolving a subject's
 * author requires the owning module's lookup API (reporting/engagement/communications), which this module
 * must not import (ARCHITECTURE.md §3.2). It is a {@code // TODO(wiring)} to populate it from a published
 * subject-author lookup so the D16 self-action guard on the action endpoint has the author to compare
 * against; until then that guard is vacuously satisfied for author-less subjects (deny-by-default still
 * applies to the moderator role itself).</p>
 */
@Service
public class FlagService {

    private final FlagRepository flagRepository;
    private final ModerationItemRepository itemRepository;
    private final SeverityPolicy severityPolicy;
    private final ClockPort clock;

    /**
     * @param flagRepository flag store (dedup + persistence).
     * @param itemRepository queue store (one-live-item-per-subject collapse).
     * @param severityPolicy reason→severity classification (§25.8).
     * @param clock          time source for SLA stamping (testable).
     */
    public FlagService(FlagRepository flagRepository,
                       ModerationItemRepository itemRepository,
                       SeverityPolicy severityPolicy,
                       ClockPort clock) {
        this.flagRepository = flagRepository;
        this.itemRepository = itemRepository;
        this.severityPolicy = severityPolicy;
        this.clock = clock;
    }

    /**
     * Flags content on behalf of the authenticated citizen.
     *
     * @param flaggerProfileId the flagging citizen's profile public id (from the security context).
     * @param request          the validated flag request.
     * @return the created {@link FlagDto} (so the flagger gets feedback — US-12.1).
     * @throws ApiException {@link ErrorCode#CONFLICT} if this citizen already flagged this subject.
     */
    @Transactional
    public FlagDto flag(UUID flaggerProfileId, FlagContentRequest request) {
        // Anti-brigading: one live flag per (citizen, subject). The DB UNIQUE index is the hard backstop;
        // this pre-check returns a clean CONFLICT rather than a raw constraint violation on the common path.
        if (flagRepository.existsByFlaggerProfileIdAndSubjectTypeAndSubjectId(
                flaggerProfileId, request.subjectType(), request.subjectId())) {
            throw new ApiException(ErrorCode.CONFLICT);
        }

        ModerationSeverity severity = severityPolicy.initialSeverity(request.reason());

        // Collapse: attach to the live queue item for this subject, or open a new one.
        ModerationItem item = itemRepository
                .findBySubjectTypeAndSubjectId(request.subjectType(), request.subjectId())
                .orElseGet(() -> ModerationItem.open(request.subjectType(), request.subjectId(),
                        // TODO(wiring): resolve the subject's author profile id via the owning module's
                        // published lookup API so the D16 self-action guard can compare against it.
                        null, severity, clock.now()));
        item.recordFlag(severity, clock.now());
        ModerationItem savedItem = itemRepository.save(item);

        Flag flag = Flag.open(request.subjectType(), request.subjectId(), flaggerProfileId,
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
