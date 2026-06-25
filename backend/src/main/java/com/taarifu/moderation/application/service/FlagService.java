package com.taarifu.moderation.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
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
 * existing one), seeding severity from the {@link SeverityPolicy}, (c) records the {@link Flag}, and
 * (d) runs the <b>auto-assist screen</b> on the flagged content (US-12.3, UC-H05; ADR-0018) so the queue
 * item is also prioritised by what the content actually contains, not only the flag reason.</p>
 *
 * <p><b>🔒 Auto-assist is assist only — it never silences content (D-Q8, R21; ADR-0018).</b> After the item
 * is raised, this service asks the owning module for the subject's <b>scorable text</b> (via the published
 * {@link SubjectContentResolver} → {@code SubjectContentQueryApi}, the same boundary-safe registry pattern as
 * the author lookup) and hands it to {@link AutoAssistService#triage}. The scorer can only <b>hold-and-
 * prioritise</b> a risky item for a <i>human</i> review — it has no path to a takedown; only the D16-guarded
 * human action endpoint can action. If no owner publishes a content port for the subject type (the launch
 * reality), or the scorer is the degraded no-provider stub, the screen is a no-op and the flagged item still
 * goes to a human (EI-18 — the human pipeline is always the floor). The flag itself is never blocked by
 * auto-assist.</p>
 *
 * <p>WHY the screen runs <b>inside</b> the flag transaction (not on a separate path): the item this flag
 * raised is the very item the screen marks/escalates — running both in one transaction keeps the queue item
 * consistent (one save reflects both the flag and any auto-hold) and the triage analytics fact rides the same
 * outbox commit. {@link AutoAssistService#triage} is {@code @Transactional} (propagation REQUIRED) so it
 * joins this transaction rather than opening a second one.</p>
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
    private final SubjectContentResolver subjectContentResolver;
    private final AutoAssistService autoAssistService;
    private final ClockPort clock;
    private final OutboxWriter outboxWriter;

    /**
     * @param flagRepository         flag store (dedup + persistence).
     * @param itemRepository         queue store (one-live-item-per-subject collapse).
     * @param severityPolicy         reason→severity classification (§25.8).
     * @param subjectAuthorResolver  resolves the subject's author via the owning module's published port
     *                               (ADR-0013 §4c) so the D16 self-action guard has the author to compare.
     * @param subjectContentResolver resolves the subject's scorable text via the owning module's published
     *                               {@code SubjectContentQueryApi} (ADR-0018) so the auto-assist screen can run
     *                               without moderation importing the content owner; empty → screen skipped.
     * @param autoAssistService      the assist-only screen ({@link AutoAssistService#triage}) — holds-and-
     *                               prioritises risky content for a human, never actions/removes (D-Q8, R21).
     * @param clock                  time source for SLA stamping (testable).
     * @param outboxWriter           the transactional-outbox port; {@link #flag} appends a
     *                               {@code content_flagged} analytics fact in the flag transaction so the
     *                               analytics sink records it asynchronously, off the citizen's path — the
     *                               numerator of the abuse-report-rate KPI (Appendix E, M15; ADR-0013 §2).
     */
    public FlagService(FlagRepository flagRepository,
                       ModerationItemRepository itemRepository,
                       SeverityPolicy severityPolicy,
                       SubjectAuthorResolver subjectAuthorResolver,
                       SubjectContentResolver subjectContentResolver,
                       AutoAssistService autoAssistService,
                       ClockPort clock,
                       OutboxWriter outboxWriter) {
        this.flagRepository = flagRepository;
        this.itemRepository = itemRepository;
        this.severityPolicy = severityPolicy;
        this.subjectAuthorResolver = subjectAuthorResolver;
        this.subjectContentResolver = subjectContentResolver;
        this.autoAssistService = autoAssistService;
        this.clock = clock;
        this.outboxWriter = outboxWriter;
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
            // ANALYTICS (Appendix E, M15; ADR-0013 §2): append a content_flagged civic-activity fact to the
            // outbox in THIS transaction — the analytics sink records it ASYNCHRONOUSLY, off the citizen's
            // path, as the numerator of the abuse-report-rate KPI. The flag reason rides as the
            // controlled-vocabulary `outcome` code (e.g. ABUSE/HARASSMENT); the active role is CITIZEN. Ids/
            // codes ONLY — NO flagger identity, NO content body, NO free-text detail (PRD §18, PDPA,
            // ADR-0014 §1). The aggregate is the queue item's public id (the live item this flag attached to).
            outboxWriter.append(EventEnvelope.of(
                    AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                    AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY,
                    savedItem.getPublicId(),
                    new CivicActivityRecorded(
                            AnalyticsEventTypes.CONTENT_FLAGGED,
                            clock.now(),
                            null,                       // actorRef: no pseudonymous flagger hash resolved here
                            null,                       // geoAreaId: moderation is not geo-scoped
                            null,                       // categoryId: n/a for a flag
                            null,                       // tier: n/a
                            null,                       // channel: n/a (server-side action)
                            "CITIZEN",                  // activeRole name (string — NOT the analytics enum; ADR-0013 §3)
                            null,                       // latencySeconds: n/a
                            null,                       // breachType: n/a
                            request.reason().name()),   // outcome = the flag reason (controlled vocab)
                    clock.now()));

            // AUTO-ASSIST SCREEN (US-12.3, UC-H05, D-Q8; ADR-0018): now that this flag has raised the queue
            // item, run the content-safety screen on the flagged content so the item is also prioritised by
            // what the content actually contains. The scorable text is fetched from the owning module's
            // published content port (the boundary-safe registry pattern — moderation never imports the
            // owner). triage() is assist ONLY: it can hold-and-prioritise the SAME item for a HUMAN review
            // and emit the auto_moderation_triaged analytics fact — it has NO path to a takedown (only the
            // D16-guarded human action endpoint can action). It joins THIS transaction (propagation REQUIRED).
            //
            // 🔒 Graceful degradation (EI-18): if no owner publishes a content port for this subject type
            // (the launch reality) the text resolves empty and we SKIP the screen — the flagged item still
            // goes to a human moderator (the human pipeline is the floor). The flag is NEVER blocked or
            // failed by auto-assist. The transient text is handed straight to the scorer and never persisted
            // or logged here (PRD §18, PDPA). The author (account public id) is passed so the held item keeps
            // the D16 self-action grain for when a human later actions it; null for an author-less subject.
            subjectContentResolver.contentTextOf(request.subjectType(), request.subjectId())
                    .ifPresent(text -> autoAssistService.triage(
                            request.subjectType(), request.subjectId(),
                            savedItem.getSubjectAuthorProfileId(), text, null));

            return FlagDto.from(saved);
        } catch (DataIntegrityViolationException dup) {
            // Lost the race against a concurrent identical flag — surface the same clean CONFLICT.
            throw new ApiException(ErrorCode.CONFLICT, dup);
        }
    }
}
