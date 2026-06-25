/**
 * Moderation &amp; Trust-and-Safety module — M12 (PRD §18, §25.8, Epic M12; ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: the platform's content-safety spine — citizens <b>flag</b> content; flags land in a
 * severity-prioritised <b>moderation queue</b> ({@code ModerationItem}); moderators take an append-only
 * <b>action</b> ({@code ModerationAction}: approve/hide/remove/warn/suspend/verify-request); and the
 * affected party may <b>appeal</b>, which is handled by a <i>different</i> moderator (appeal independence,
 * §25.8, Appendix F footnote ᵉ). Severity-based review SLAs are carried on the queue item (GBV/safety
 * ≤ hours; abuse/PII/spam ≤24h; general ≤72h — §25.8).</p>
 *
 * <p>Internal layering (ARCHITECTURE.md §3.3): {@code api → application → domain}. All moderated content
 * is referenced <b>by {@code (subjectType, subjectId UUID)} only</b> — this module never imports
 * reporting/engagement/communications/institutions (their entities live behind their own boundaries);
 * resolving a subject to a concrete record is done boundary-safely through the owners' published lookup
 * registries ({@code SubjectAuthorQueryApi} for the D16 author, {@code SubjectContentQueryApi} for the
 * scorable text — ADR-0013 §4c rule 3), each dispatched by {@code subjectType}; an owner that has not yet
 * published its port resolves to empty and the item still reaches a human (EI-18 floor). It depends only on
 * {@code common}, {@code identity}, and {@code analytics} public surfaces.</p>
 *
 * <p>Integrity invariant (D16, §25.8): a moderator may not moderate their own content, nor handle an
 * appeal of their own action — enforced via {@code @taarifuAuthz.isNotSelf(...)} on the queue-action
 * endpoint and a distinct-moderator check on the appeal-decision endpoint, both audited.</p>
 *
 * <p><b>Auto-assist (US-12.3, UC-H05, EI-18, D-Q8; ADR-0018):</b> the automated half of the hybrid model.
 * A pluggable {@code ContentSafety} port ({@code HeuristicContentSafetyScorer} default — Swahili+English,
 * evasion-normalised, conservative-threshold, zero external calls; a real ML adapter swaps in later) scores
 * content; {@code AutoAssistService} <b>holds-and-prioritises</b> risky content for human review (reusing the
 * §25.8 {@code SeverityPolicy} SLA chain) and emits an {@code auto_moderation_triaged} fact. It is <b>assist
 * only — it never auto-removes</b> (R21): only the D16-guarded human action path can take a takedown, and when
 * no provider scores anything everything routes to human moderators (EI-18 degradation). The human pipeline is
 * always the floor. The screen is <b>wired into the flag path</b> ({@code FlagService}): when a citizen flag
 * raises a queue item, the flagged content is scored and the item is auto-marked/escalated for a human —
 * the scorable text is fetched via the owner's published {@code SubjectContentQueryApi} (the same registry
 * pattern as the author lookup), and when no owner publishes a content port the screen is simply skipped (the
 * flag still raises a human-reviewed item). PHASE-3: auto-assist screening on content <i>create</i> (before any
 * flag) and the sensitive-report pre-routing hold need each content-owning module to call
 * {@code AutoAssistService.triage(...)} on its own create/publish path — the receiving seam (that
 * {@code @Transactional} bean + the {@code SubjectContentQueryApi} registry) is published and ready; moderation
 * cannot trigger it from here without importing the owner (ARCHITECTURE.md §3.2).</p>
 *
 * <p><b>Transparency reporting (§18, §25, M-Phase 3; ADR-0018):</b> {@code TransparencyReportService} +
 * {@code GET /moderation/transparency} aggregate moderation's own (append-only, tamper-evident) tables into a
 * <b>PII-free</b> report — action mix, appeal outcomes, flags-by-reason, and the auto-vs-manual split — keyed
 * only on codes/enums (no person, location, or content), safe to publish.</p>
 */
package com.taarifu.moderation;
