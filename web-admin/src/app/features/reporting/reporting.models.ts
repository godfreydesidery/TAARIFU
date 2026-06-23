/**
 * Reporting & case-management DTOs — mirror the backend `ReportDto`, `PublicReportDto`, `CaseEventDto`,
 * and the responder case-lifecycle request bodies (reporting + responders modules; PRD §9.1, §12.1,
 * Epic M3). These shape the official report queue + case detail/timeline + status actions.
 *
 * <p>WHY two read shapes: the admin/responder case view reads the rich {@link Report} (owner-grade
 * fields), while the public near-me queue reads the PII-free {@link PublicReport}. The console queue is
 * built on the public list (the only paged list endpoint the backend exposes for reports today — there is
 * no admin "list all reports" endpoint yet; see CENTRAL NEEDS) and drills into the responder lifecycle
 * actions for case management.</p>
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
 * The PII-free public report row (`GET /public/reports`). Backs the console queue list. It never carries
 * reporter id or precise geo; only PUBLIC reports are returned (PRD §25.3).
 */
export interface PublicReport {
  /** The report's public id (UUID). */
  id: string;
  /** Human-readable ticket code. */
  code: string;
  /** Issue category public id. */
  categoryId: string;
  /** Issue category display name. */
  categoryName: string | null;
  /** Title. */
  title: string;
  /** Ward public id, or `null`. */
  wardId: string | null;
  /** Case status token. */
  status: string;
  /** Priority token. */
  priority: string;
  /** SLA due instant (ISO-8601), or `null`. */
  dueAt: string | null;
  /** Filed-at instant (ISO-8601). */
  createdAt: string;
}

/** A case-timeline event (`GET /reports/{id}/timeline`). One row per status change/assignment/comment. */
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
