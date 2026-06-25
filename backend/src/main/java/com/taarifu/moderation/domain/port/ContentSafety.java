package com.taarifu.moderation.domain.port;

import com.taarifu.moderation.api.FlagSubjectType;
import com.taarifu.moderation.domain.model.enums.ContentSignal;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for <b>content-safety auto-assist</b> — the automated half of the hybrid moderation model
 * (PRD §12 US-12.3, UC-H05, EI-18, D-Q8; ARCHITECTURE.md §7).
 *
 * <p>Responsibility: abstracts "score this content for risk" so the auto-assist pipeline never depends on a
 * concrete classifier. An adapter returns <b>risk labels + confidences only</b> (EI-18 "In: risk
 * scores/labels") — it does <b>not</b> decide a takedown. The conservative Swahili+English heuristic adapter
 * is the match-if-missing default; a real ML/hosted Swahili-aware classifier swaps in behind this same port
 * later (ADR-0018) with no caller change.</p>
 *
 * <p><b>🔒 Assist only — never auto-remove (D-Q8, R21).</b> The result can cause a queue item to be
 * <i>held for review</i> and prioritised; it can <b>never</b> approve/hide/remove content or sanction an
 * account. Borderline Swahili/Sheng/code-switched content is never auto-removed — only a human moderator,
 * through the D16-guarded action path, takes a takedown decision. The human pipeline is always the floor.</p>
 *
 * <p><b>🔒 Degradation (EI-18).</b> When no real provider is configured, the stub adapter returns an
 * {@link ContentSafetyResult#empty() empty} result, so <b>everything routes to human moderators + community
 * flagging</b>. The implementation MUST NOT throw for a routine scoring failure — degrade to "no signal"
 * (empty result) so the citizen/moderation path never hard-fails on a provider outage.</p>
 *
 * <p><b>🔒 PII / content discipline (PRD §18, PDPA).</b> The {@code text} is handled transiently inside the
 * scoring call only — an adapter MUST NOT persist it, and MUST NOT log the content body or any detected PII
 * (only non-PII signal counts, at debug). The port is in {@code domain.port} and carries no vendor type
 * (ArchUnit {@code domainPortsHaveNoVendorImports}).</p>
 */
public interface ContentSafety {

    /**
     * Scores one piece of content for safety risk.
     *
     * @param request the content to score (subject reference + transient text + optional language hint).
     * @return the detected {@link SafetySignal}s with confidences; never {@code null} — an empty result
     *         means "no auto-assist signal" (the human pipeline floor). Never throws for a routine failure.
     */
    ContentSafetyResult score(ContentSafetyRequest request);

    /**
     * A request to score a piece of content.
     *
     * @param subjectType  the kind of content (for any subject-type-specific rules; e.g. IMAGE only for media).
     * @param subjectId    the content's public id (cross-module reference; for diagnostics/correlation only).
     * @param text         the content body to scan — handled transiently, <b>never persisted/logged</b>.
     * @param languageHint an optional locale/UGC language tag (e.g. {@code "sw"}/{@code "en"}) to bias the
     *                     lexicon; {@code null} when unknown (the heuristic scans both languages).
     */
    record ContentSafetyRequest(FlagSubjectType subjectType, UUID subjectId, String text, String languageHint) {
    }

    /**
     * One detected risk signal and the scorer's confidence in it.
     *
     * @param signal     the risk label (the published Appendix E vocabulary).
     * @param confidence the scorer's confidence in {@code [0.0, 1.0]} — the auto-assist policy compares this
     *                   against the configurable, conservative hold threshold (R21).
     */
    record SafetySignal(ContentSignal signal, double confidence) {
    }

    /**
     * The outcome of scoring: the detected signals and the provider's own conservative hold recommendation.
     *
     * @param signals       the detected signals (possibly empty); never {@code null}.
     * @param recommendHold the provider's recommendation to hold for human review. Advisory — the
     *                      auto-assist policy makes the final hold decision against its configured threshold;
     *                      a hold <b>never</b> removes content (assist only).
     */
    record ContentSafetyResult(List<SafetySignal> signals, boolean recommendHold) {

        /** @return an empty result — "no auto-assist signal"; the degraded / no-provider default (EI-18). */
        public static ContentSafetyResult empty() {
            return new ContentSafetyResult(List.of(), false);
        }

        /**
         * @return the highest-confidence signal, or {@code null} if there are none. The auto-assist
         *         pipeline records this top signal on the held queue item (one signal per item for the
         *         analytics split; the full set stays advisory to the moderator).
         */
        public SafetySignal topSignal() {
            return signals.stream().max((a, b) -> Double.compare(a.confidence(), b.confidence())).orElse(null);
        }
    }
}
