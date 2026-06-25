/**
 * Reporting module — M3 Issue Reporting &amp; Case Management (PRD §10 Epic M3, §12.1, §25.3,
 * Appendix D; ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: the hierarchical issue taxonomy ({@code IssueCategory} with default
 * routing/SLA/sensitivity/visibility), the {@code Report} ticket aggregate (human code
 * {@code TAR-YYYY-NNNNNN} via a DB sequence + {@code common.CodeGenerator}), and the append-only
 * {@code CaseEvent} timeline. It owns the §12.1 report state machine (NEW→ASSIGNED→IN_PROGRESS→
 * AWAITING_INFO→RESOLVED→CLOSED, plus REOPENED/REJECTED/DUPLICATE/ESCALATED) with server-side
 * transition guards, and the citizen flows: file (T2; sensitive categories force PRIVATE and allow
 * anonymous per D-Q1), track own report + timeline, add info/comment, confirm/dispute resolution, and
 * the PII-free public near-me reports list/map (US-3.7).</p>
 *
 * <p>Layered as {@code api} / {@code application} / {@code domain} / {@code infrastructure}
 * (FOUNDATION-SCOPE.md §1). It depends on {@code common} (kernel) and {@code geography} (ward→constituency
 * resolution via the {@code WardResolver} port → {@code GeographyQueryService}) only through their public
 * APIs — never their tables (ARCHITECTURE.md §4.3).</p>
 *
 * <p>Cross-module routing is <b>wired both ways</b> over the transactional outbox (ADR-0014 §5b, D21):
 * filing emits {@code REPORT_ROUTED} (consumed by the responders {@code RoutingHandler}, which creates the
 * OWNER assignment and emits {@code RESPONDER_ASSIGNED} back), and the reporting
 * {@code ResponderAssignedHandler} consumes that back-event to set {@code Report.assignedResponderId} and
 * transition {@code NEW -> ASSIGNED} idempotently — closing the round-trip on the report side. Report
 * lifecycle/analytics facts are emitted on the same outbox. STILL DEFERRED to later increments: SLA-breach
 * escalation scheduling and the attachment virus-scan hook. All cross-module seams reference other modules
 * by id (UUID) only and carry no PII (PRD §18).</p>
 *
 * <p><b>Discovery &amp; moderation seams (ADR-0017, ADR-0018; ADR-0013 §1/§4c):</b> reporting <b>pushes</b> a
 * public, PII-free projection of a report (title, description snippet, ward area + category facets) into the
 * search module's index via {@code search.api.SearchIndexApi} on file and on every lifecycle change, and
 * <b>removes</b> it whenever the report is not (or no longer) public-safe — a PRIVATE, sensitive, or anonymous
 * report is <b>never</b> indexed (the discovery IDOR fence — PRD §25.3, PDPA), and a PDPA erasure that
 * anonymises a report removes its discovery row. Reporting also <b>publishes</b> {@code moderation.api}'s
 * {@code SubjectContentQueryApi} for {@code FlagSubjectType.REPORT}, surfacing a flagged report's transient
 * scorable text (title + description) to moderation's auto-assist scorer — text only, scored and discarded,
 * never persisted or logged (PRD §18). Both are owner→foundation {@code api} edges (no reach-in, no cycle).</p>
 */
package com.taarifu.reporting;
