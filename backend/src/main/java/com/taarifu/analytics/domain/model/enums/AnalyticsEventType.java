package com.taarifu.analytics.domain.model.enums;

/**
 * The canonical catalogue of product-analytics event types the platform records (PRD Appendix E.1;
 * M15, ARCHITECTURE.md §8 outbox-driven analytics sink).
 *
 * <p>Responsibility: the stable, append-only vocabulary of "something measurable happened" that other
 * modules record through {@link com.taarifu.analytics.api.AnalyticsApi} and that the aggregation
 * endpoints group over. The names mirror Appendix E's {@code noun_verb_pastTense} catalogue (here the
 * subset M15 needs to power the §3.3 KPIs and the Appendix C dashboards: reporting volume + TTFR/TTR,
 * SLA breaches, the T0→T3 verification funnel, channel mix, engagement counts, and moderation actions).</p>
 *
 * <p>WHY an enum (not a free-form string): the analytics_event table stores the {@code event_type} as a
 * {@code VARCHAR} drawn from these names, so a typo at a call site is impossible and the dashboard query
 * surface is reviewable in one place (DRY; CLAUDE.md §3). It is <b>append-only</b> — never rename or
 * repurpose a value, because historical rows already carry it (Appendix E.0 "stable forever; additive
 * evolution only").</p>
 *
 * <p>WHY only a subset (not every Appendix E row): M15 builds the aggregation surface for the headline
 * KPIs; the long tail (e.g. {@code feed_item_viewed}, {@code search_performed}) can be added additively
 * later without a schema change, since the column is a {@code VARCHAR}. Live emission from sibling modules is
 * now wired through the transactional outbox: producers append a {@code CivicActivityRecorded} fact and
 * {@code AnalyticsEventHandler} records it here (ADR-0013 §2). PHASE-3: the long-tail catalogue values land
 * additively as their owner modules begin emitting them — the handler's unknown-tolerant parse already drops
 * any value an older build does not yet know (Appendix E.0).</p>
 */
public enum AnalyticsEventType {

    // --- Identity, onboarding & verification funnel (T0→T3) ---

    /** Account created at T1 after OTP (Appendix E: {@code account_signed_up}). Verification-funnel entry. */
    ACCOUNT_SIGNED_UP,

    /** Profile completed, reaching T2 (Appendix E: {@code profile_completed}). T1→T2 step. */
    PROFILE_COMPLETED,

    /** ID verification submitted (Appendix E: {@code identity_verification_started}). */
    IDENTITY_VERIFICATION_STARTED,

    /** T3 reached — provider success or moderator approval (Appendix E: {@code identity_verified}). */
    IDENTITY_VERIFIED,

    /** Verification rejected/dedup-blocked (Appendix E: {@code identity_verification_failed}). Funnel drop-off. */
    IDENTITY_VERIFICATION_FAILED,

    // --- Issue reporting & case management (volume, TTFR/TTR, SLA) ---

    /** Report submitted, ticket issued (Appendix E: {@code report_filed}). Reporting-volume metric. */
    REPORT_FILED,

    /** System auto-routed a report (Appendix E: {@code report_routed}). Routing/ops health. */
    REPORT_ROUTED,

    /** First official action after filing (Appendix E: {@code report_first_responded}). Carries TTFR. */
    REPORT_FIRST_RESPONDED,

    /** Case state transition (Appendix E: {@code report_status_changed}). State-machine analytics. */
    REPORT_STATUS_CHANGED,

    /** Status set RESOLVED (Appendix E: {@code report_resolved}). Carries TTR. */
    REPORT_RESOLVED,

    /** Citizen confirmed resolution → CLOSED (Appendix E: {@code report_confirmed}). Loop closure. */
    REPORT_CONFIRMED,

    /** Citizen disputed → REOPENED/ESCALATED (Appendix E: {@code report_disputed}). */
    REPORT_DISPUTED,

    /** SLA breach or manual escalation (Appendix E: {@code report_escalated}). Carries breach type. SLA heatmap. */
    REPORT_ESCALATED,

    // --- Engagement ---

    /** T3 citizen signed a petition once (Appendix E: {@code petition_signed}). */
    PETITION_SIGNED,

    /** Citizen responded to a survey/poll (Appendix E: {@code survey_responded}). */
    SURVEY_RESPONDED,

    /** T2 citizen asked a representative a question (Appendix E: {@code question_asked}). */
    QUESTION_ASKED,

    /** Representative answered a question (Appendix E: {@code question_answered}). */
    QUESTION_ANSWERED,

    /** T3 citizen rated a representative (Appendix E: {@code rep_rated}). */
    REP_RATED,

    /** Follow/unfollow of an area/category/rep/project (Appendix E: {@code subscription_changed}). */
    SUBSCRIPTION_CHANGED,

    // --- Channel & session (Reach / channel mix) ---

    /** App/web/PWA/USSD session opened (Appendix E: {@code session_started}). Channel-mix metric. */
    SESSION_STARTED,

    // --- Moderation, trust & safety ---

    /** User flagged content (Appendix E: {@code content_flagged}). Abuse-rate metric. */
    CONTENT_FLAGGED,

    /** Moderator acted on content (Appendix E: {@code moderation_action_taken}). Moderation-actions metric. */
    MODERATION_ACTION_TAKEN,

    /** Moderation appeal decided — UPHELD/OVERTURNED (Appendix E: {@code moderation_appeal_resolved}). Closes
     * the trust-and-safety funnel and powers the appeal-overturn-rate (moderation-quality) signal. */
    MODERATION_APPEAL_RESOLVED,

    /**
     * Auto-assist screened a piece of flagged/created content and either held it for human review or let it
     * through (Appendix E: {@code auto_moderation_triaged}; ADR-0018; US-12.3; UC-H05).
     *
     * <p>Emitted by {@code moderation.AutoAssistService} on the outbox as a {@code CivicActivityRecorded} fact
     * whose {@code analyticsEventType} string is exactly {@code "AUTO_MODERATION_TRIAGED"} (the value of
     * {@code moderation.api.event.ModerationEventTypes#AUTO_MODERATION_TRIAGED}). It powers the
     * <b>auto-vs-manual moderation split</b> KPI — the share of items the automated screen prioritised versus
     * those a human/flagger surfaced (US-12.3; R20/R22, "moderation at scale").</p>
     *
     * <p>Dimension mapping for this fact (no schema change — ADR-0018 §3): the top safety
     * {@code ContentSignal} (PROFANITY/PII/SPAM/IMAGE) rides as the controlled-vocabulary {@link
     * com.taarifu.analytics.domain.model.AnalyticsEvent#getOutcome() outcome} code, and whether the item was
     * held rides as the {@code breachType} field ({@code HELD}/{@code NOT_HELD}); since those two strings are
     * not members of {@link BreachType}, the event handler's unknown-tolerant parse maps them to {@code null}
     * (safely ignored) while the signal is preserved verbatim in {@code outcome}. Ids/codes only — no content
     * body, author, or confidence-revealing text (PRD §18, PDPA, Appendix E.4).</p>
     *
     * <p><b>WHY this value was added late (V171):</b> before it existed the analytics handler dropped the fact
     * as a forward-compatible no-op (Appendix E.0 additive — see {@code AnalyticsEventHandler}); adding the
     * value here is what makes the auto-triage fact actually persist and become queryable. The catalogue stays
     * append-only — never rename or repurpose a value (historical rows carry it).</p>
     */
    AUTO_MODERATION_TRIAGED
}
