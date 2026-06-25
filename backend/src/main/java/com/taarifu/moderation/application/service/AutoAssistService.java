package com.taarifu.moderation.application.service;

import com.taarifu.analytics.api.event.AnalyticsEventTypes;
import com.taarifu.analytics.api.event.CivicActivityRecorded;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.api.dto.AutoAssistResultDto;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.domain.model.ModerationItem;
import com.taarifu.moderation.domain.model.enums.ContentSignal;
import com.taarifu.moderation.domain.model.enums.ModerationSeverity;
import com.taarifu.moderation.domain.port.ContentSafety;
import com.taarifu.moderation.domain.repository.ModerationItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Use-case service for <b>auto-assist content triage</b> — the automated half of the hybrid moderation model
 * (PRD §12 US-12.3, UC-H05, EI-18, D-Q8; ADR-0018).
 *
 * <p>Responsibility: scores a piece of content through the {@link ContentSafety} port and, when the risk is
 * at/above the configurable conservative hold threshold (R21), <b>holds it for human review</b> — by opening
 * or escalating the subject's {@link ModerationItem} (reusing the one-live-item-per-subject collapse and the
 * §25.8 {@link SeverityPolicy} SLA chain) and marking it auto-assisted with the top signal + confidence. It
 * then emits an {@code auto_moderation_triaged} analytics fact on the outbox.</p>
 *
 * <p><b>🔒 Assist only — never auto-removes (D-Q8, R21).</b> This service has <b>no path</b> to a
 * {@link com.taarifu.moderation.domain.model.ModerationAction}: it can open/escalate/mark a queue item and
 * emit analytics, and nothing else. The only code that approves/hides/removes/suspends is
 * {@link ModerationQueueService#takeAction} — a <b>human</b> moderator action, guarded by the D16
 * conflict-of-interest fence. So borderline Swahili/Sheng/code-switched content is never silenced by a
 * machine; the human pipeline + community flagging is always the floor.</p>
 *
 * <p><b>🔒 Degradation (EI-18).</b> When the scorer returns no signal (no provider / below threshold), this
 * service holds nothing and returns — the content flows on to the human pipeline. The scorer never throws for
 * a routine failure, so a provider outage degrades to all-to-human, never a hard fail on the content path.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA).</b> The {@code text} is passed straight to the scorer and never
 * persisted or logged here; the queue item records only labels (signal + confidence), and the analytics fact
 * carries ids/codes only — no text, no author identity.</p>
 *
 * <p>WHO calls {@code triage(...)}: (1) the moderation module's own <b>flag path</b>
 * ({@link FlagService#flag}) — when a citizen flag raises/escalates a {@link ModerationItem}, the flagged
 * content is screened so the item is also prioritised by what it actually contains. The scorable text is
 * fetched from the owning module's published {@code SubjectContentQueryApi} (the boundary-safe registry — no
 * content-owner import); when no such port is published the screen is skipped and the item still goes to a
 * human (EI-18 floor). This is the live wiring. (2) Content-owning modules' create paths (to screen on
 * publish, before any flag) and the §25.3 sensitive-report stricter pre-routing hold remain
 * {@code // TODO(wiring)} until those owners call in — the same deferral discipline as the existing
 * routing/takedown wirings (ADR-0013 §2).</p>
 */
@Service
public class AutoAssistService {

    private final ContentSafety contentSafety;
    private final ModerationItemRepository itemRepository;
    private final SeverityPolicy severityPolicy;
    private final ClockPort clock;
    private final OutboxWriter outboxWriter;

    /**
     * The conservative confidence threshold at/above which the top signal causes a hold (R21). Configurable
     * via {@code taarifu.moderation.content-safety.hold-threshold} (default {@code 0.80}); below it nothing is
     * held — a human/flagger still can. Kept high deliberately so the heuristic never over-holds.
     */
    private final double holdThreshold;

    /**
     * @param contentSafety  the pluggable content-risk scorer port (heuristic default; ML later).
     * @param itemRepository queue store (one-live-item-per-subject collapse).
     * @param severityPolicy reason/signal → severity classification (§25.8) — reused so auto-held items share
     *                       the same prioritised queue and SLA chain (DRY).
     * @param clock          time source for SLA stamping (testable).
     * @param outboxWriter   the transactional-outbox port; {@link #triage} appends an
     *                       {@code auto_moderation_triaged} analytics fact in the triage transaction so the
     *                       analytics sink records the auto-vs-manual split asynchronously (Appendix E, M15).
     * @param holdThreshold  the configurable conservative hold threshold (default {@code 0.80}).
     */
    public AutoAssistService(ContentSafety contentSafety,
                             ModerationItemRepository itemRepository,
                             SeverityPolicy severityPolicy,
                             ClockPort clock,
                             OutboxWriter outboxWriter,
                             @Value("${taarifu.moderation.content-safety.hold-threshold:0.80}") double holdThreshold) {
        this.contentSafety = contentSafety;
        this.itemRepository = itemRepository;
        this.severityPolicy = severityPolicy;
        this.clock = clock;
        this.outboxWriter = outboxWriter;
        this.holdThreshold = holdThreshold;
    }

    /**
     * Scores content and, if risky enough, holds it for human review (US-12.3, UC-H05).
     *
     * @param subjectType            the kind of content.
     * @param subjectId              the content's public id (cross-module reference).
     * @param subjectAuthorAccountId the content author's <b>account</b> public id (JWT-subject grain) for the
     *                               D16 self-action guard when a human later actions the held item, or
     *                               {@code null} when the subject has no surfaced author (e.g. an anonymous
     *                               sensitive report — D-Q1; the guard is then vacuously satisfied).
     * @param text                   the content body to scan — passed transiently, never persisted/logged.
     * @param languageHint           an optional UGC language tag to bias the lexicon, or {@code null}.
     * @return an {@link AutoAssistResultDto} describing whether a hold was raised and the top signal — a
     *         decision record for callers/tests; it carries no content or author identity.
     */
    @Transactional
    public AutoAssistResultDto triage(FlagSubjectType subjectType, UUID subjectId,
                                      UUID subjectAuthorAccountId, String text, String languageHint) {
        ContentSafety.ContentSafetyResult result = contentSafety.score(
                new ContentSafety.ContentSafetyRequest(subjectType, subjectId, text, languageHint));

        ContentSafety.SafetySignal top = result.topSignal();
        boolean held = top != null && top.confidence() >= holdThreshold;

        if (held) {
            ContentSignal signal = top.signal();
            ModerationSeverity severity = severityPolicy.severityForSignal(signal);

            // Collapse: attach to the live queue item for this subject, or open a new one held for review.
            // markAutoAssisted() ONLY raises/holds — it never closes or actions the item (assist only, R21).
            ModerationItem item = itemRepository
                    .findBySubjectTypeAndSubjectId(subjectType, subjectId)
                    .orElseGet(() -> ModerationItem.open(subjectType, subjectId, subjectAuthorAccountId,
                            severity, clock.now()));
            item.markAutoAssisted(signal, top.confidence(), severity, clock.now());
            itemRepository.save(item);
        }

        // ANALYTICS (Appendix E, M15; ADR-0013 §2): append an auto_moderation_triaged civic-activity fact to
        // the outbox in THIS transaction — the analytics sink records the auto-vs-manual split asynchronously,
        // off the content path. The top signal rides as the controlled-vocabulary `outcome` code
        // (PROFANITY/PII/SPAM/IMAGE), the breachType field carries the held flag (HELD/NOT_HELD) so the KPI
        // can split auto-vs-manual without a schema change. Ids/codes ONLY — NO content body, NO author
        // identity, NO confidence-revealing free text (PRD §18, PDPA, ADR-0014 §1). The aggregate is the
        // subject's public id. The analytics catalogue value is forward-compatible: until the analytics enum
        // gains AUTO_MODERATION_TRIAGED the handler drops it as a no-op (Appendix E.0 additive; CENTRAL NEED).
        outboxWriter.append(EventEnvelope.of(
                AnalyticsEventTypes.CIVIC_ACTIVITY_RECORDED,
                AnalyticsEventTypes.AGGREGATE_CIVIC_ACTIVITY,
                subjectId,
                new CivicActivityRecorded(
                        ModerationEventTypes.AUTO_MODERATION_TRIAGED,
                        clock.now(),
                        null,                                       // actorRef: auto-assist is system-run, no actor hash
                        null,                                       // geoAreaId: moderation is not geo-scoped
                        null,                                       // categoryId: n/a for an auto-triage
                        null,                                       // tier: n/a
                        null,                                       // channel: n/a (server-side screen)
                        "MODERATOR",                                // activeRole: a moderation-pipeline fact
                        null,                                       // latencySeconds: n/a
                        held ? "HELD" : "NOT_HELD",                 // breachType field reused to carry held (bool)
                        top != null ? top.signal().name() : null),  // outcome = the top signal (controlled vocab)
                clock.now()));

        return new AutoAssistResultDto(held, top != null ? top.signal() : null,
                top != null ? top.confidence() : null);
    }
}
