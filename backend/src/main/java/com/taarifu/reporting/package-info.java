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
 * <p>DEFERRED to later integration (marked {@code // TODO(wiring)}): routing to the responders module
 * ({@code assignedResponderId} is a stub; reports stay {@code NEW}), SLA-breach escalation scheduling,
 * transactional-outbox emission of report domain events (ack/status-change notifications, search
 * indexing, analytics), and the attachment virus-scan hook. Those cross-module seams reference other
 * modules by id (UUID) only.</p>
 */
package com.taarifu.reporting;
