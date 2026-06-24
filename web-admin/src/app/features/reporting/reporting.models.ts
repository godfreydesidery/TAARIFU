/**
 * Reporting & case-management DTOs — mirror the backend `AdminReportSummary`/`AdminReportDetail`/
 * `CaseEventDto` (admin queue + case detail), `ReportDto`/`PublicReportDto` (lifecycle responses), and the
 * responder case-lifecycle request bodies (reporting + responders modules; PRD §9.1, §10 US-3.4, §12.1,
 * Epic M3). These shape the official report queue + case detail/timeline + status actions.
 *
 * <p>WHY the admin shapes: the back-office console reads the owner-grade {@link AdminReportSummary} (paged
 * `GET /admin/reports`, with server-side status/category/area/SLA filters) and {@link AdminReportDetail}
 * (`GET /admin/reports/{id}`, including the full internal+public timeline, US-3.4). Both are PII-minimised —
 * no reporter identity, no precise geo-point (PRD §18, PDPA, D-Q1). The lifecycle actions (assign/start/
 * resolve/escalate) return the rich owner-view {@link Report}.</p>
 */

/** Report case-lifecycle status tokens (PRD §12.1). Used for filter chips + status badges. */
export const REPORT_STATUSES = [
  'SUBMITTED',
  'TRIAGED',
  'ASSIGNED',
  'IN_PROGRESS',
  'ESCALATED',
  'RESOLVED',
  'CONFIRMED',
  'REJECTED',
  'CLOSED',
  'DUPLICATE',
] as const;

/** Report priority tokens. */
export const REPORT_PRIORITIES = ['LOW', 'NORMAL', 'HIGH', 'URGENT'] as const;

/**
 * The rich owner/responder-view report (`GET /reports/{id}` owner view; responder lifecycle responses).
 * Carries the case-management fields a moderator/responder acts on.
 */
export interface Report {
  /** The report's public id (UUID). */
  id: string;
  /** Human-readable ticket code, e.g. `TAR-2026-000123`. */
  code: string;
  /** Issue category public id. */
  categoryId: string;
  /** Issue category display name. */
  categoryName: string | null;
  /** Title (Swahili-first). */
  title: string;
  /** Free-text description. */
  description: string;
  /** Ward (Kata) public id of the incident location. */
  wardId: string | null;
  /** Derived constituency (Jimbo) public id. */
  constituencyId: string | null;
  /** Incident latitude, or `null`. */
  latitude: number | null;
  /** Incident longitude, or `null`. */
  longitude: number | null;
  /** Visibility token (`PUBLIC`/`PRIVATE`/`ANONYMOUS`). */
  visibility: string;
  /** Case status token (see {@link REPORT_STATUSES}). */
  status: string;
  /** Priority token. */
  priority: string;
  /** SLA due instant (ISO-8601), or `null`. */
  dueAt: string | null;
  /** Resolution note once resolved, or `null`. */
  resolution: string | null;
  /** Reporter confirmation outcome, or `null` if pending. */
  confirmation: boolean | null;
  /** The id this report duplicates, or `null`. */
  duplicateOfId: string | null;
  /** Upvote count. */
  upvotes: number;
  /** Follower count. */
  followers: number;
  /** Whether the report was filed anonymously (no reporter linkage shown). */
  anonymous: boolean;
  /** Filed-at instant (ISO-8601). */
  createdAt: string;
}

/**
 * One row of the admin/owner-grade report queue (`GET /admin/reports`) — mirrors the backend
 * `AdminReportSummary`. PII-minimised: no description body, no reporter linkage, no precise geo-point. The
 * {@link anonymous} flag tells staff to apply the sensitive-handling path without ever seeing who filed it.
 */
export interface AdminReportSummary {
  /** The report's public id (UUID). */
  id: string;
  /** Human ticket code (`TAR-YYYY-NNNNNN`). */
  code: string;
  /** Issue category public id. */
  categoryId: string;
  /** Issue category display name, or `null`. */
  categoryName: string | null;
  /** Citizen title/summary. */
  title: string;
  /** Resolved ward (Kata) public id, or `null`. */
  wardId: string | null;
  /** Lifecycle status name. */
  status: string;
  /** Priority name. */
  priority: string;
  /** SLA due instant (ISO-8601), or `null`. */
  dueAt: string | null;
  /** `true` if the case is still active and its SLA `dueAt` has passed (server-derived). */
  slaBreached: boolean;
  /** Assigned responder public id, or `null` if unassigned. */
  assignedResponderId: string | null;
  /** `true` if the report has no reporter linkage (anonymous sensitive filing, D-Q1). */
  anonymous: boolean;
  /** Filed-at instant (ISO-8601). */
  createdAt: string;
}

/**
 * The staff case-detail view of one report (`GET /admin/reports/{id}`) — mirrors the backend
 * `AdminReportDetail`. Includes the FULL case {@link timeline} (public + internal responder notes, US-3.4),
 * reached only by ADMIN/MODERATOR. No reporter PII, no precise geo-point (PRD §18, D-Q1).
 */
export interface AdminReportDetail {
  /** The report's public id (UUID). */
  id: string;
  /** Human ticket code. */
  code: string;
  /** Issue category public id. */
  categoryId: string;
  /** Issue category display name, or `null`. */
  categoryName: string | null;
  /** Citizen title. */
  title: string;
  /** Citizen description (operator-visible case content; not reporter PII). */
  description: string;
  /** Resolved ward (Kata) public id, or `null`. */
  wardId: string | null;
  /** Constituency (Jimbo) public id in effect, or `null`. */
  constituencyId: string | null;
  /** Effective visibility name. */
  visibility: string;
  /** Lifecycle status name. */
  status: string;
  /** Priority name. */
  priority: string;
  /** SLA due instant (ISO-8601), or `null`. */
  dueAt: string | null;
  /** `true` if the case is still active and its SLA `dueAt` has passed (server-derived). */
  slaBreached: boolean;
  /** Resolution note, or `null` if unresolved. */
  resolution: string | null;
  /** Citizen confirmation outcome: `null` pending, else `true`/`false`. */
  confirmation: boolean | null;
  /** Canonical report public id if this is a duplicate, else `null`. */
  duplicateOfId: string | null;
  /** Assigned responder public id, or `null` if unassigned. */
  assignedResponderId: string | null;
  /** `true` if the report has no reporter linkage (D-Q1). */
  anonymous: boolean;
  /** Filed-at instant (ISO-8601). */
  createdAt: string;
  /** The full case timeline (public + internal events), newest first. */
  timeline: CaseEvent[];
}

/** Per-status report count for the dashboard breakdown (mirrors the backend `ReportStatusCount`). */
export interface ReportStatusCount {
  /** Lifecycle status name. */
  status: string;
  /** Number of reports in that status. */
  count: number;
}

/**
 * The flattened aggregate-counts payload the dashboard overview header binds to (`GET /admin/stats`) —
 * mirrors the backend `AdminStatsDto`. Counts only — no PII. A degraded module returns its count as 0.
 */
export interface AdminStats {
  /** Per-status report counts (a status with no reports is omitted). */
  reportsByStatus: ReportStatusCount[];
  /** Total reports in a non-terminal status. */
  openCases: number;
  /** Total still-active reports whose SLA `dueAt` has passed. */
  slaBreachedCases: number;
  /** Number of identity verifications awaiting review (0 if unavailable). */
  verificationQueueDepth: number;
  /** Number of moderation flags awaiting action (0 if unavailable). */
  flagsPending: number;
  /** When the aggregate was computed (ISO-8601), for UI freshness. */
  generatedAt: string;
}

/** A case-timeline event (`GET /admin/reports/{id}` timeline). One row per status change/assignment/comment. */
export interface CaseEvent {
  /** The event's public id (UUID). */
  id: string;
  /** Event-type token (e.g. `STATUS_CHANGE`, `ASSIGNMENT`, `COMMENT`, `ESCALATION`). */
  eventType: string;
  /** Whether the event is citizen-visible (vs an internal note). */
  publicEvent: boolean;
  /** Acting profile public id, or `null` (system event). */
  actorProfileId: string | null;
  /** The event message/note, or `null`. */
  message: string | null;
  /** Event instant (ISO-8601). */
  createdAt: string;
}

/** Body for `POST /responders/admin/reports/{id}/assign` — assign a responder (→ ASSIGNED). */
export interface AssignCaseRequest {
  /** The responder taking the case. */
  responderId: string;
}

/** Body for `POST /responders/admin/reports/{id}/resolve` — resolve with a required note (→ RESOLVED). */
export interface ResolveCaseRequest {
  /** The required resolution note shown to the reporter. */
  resolutionNote: string;
}

/** Body for `POST /responders/admin/reports/{id}/escalate` — escalate to a supervisor (→ ESCALATED). */
export interface EscalateCaseRequest {
  /** Optional escalation reason. */
  reason?: string;
}
