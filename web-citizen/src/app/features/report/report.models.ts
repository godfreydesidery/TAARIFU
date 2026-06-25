/**
 * Reporting domain types for the citizen PWA — mirror the backend reporting DTOs (FileReportDto, ReportDto,
 * PublicReportDto, CaseEventDto, IssueCategoryDto). PRD §10 (US-3.x), Appendix D.
 */

/** Lifecycle status names emitted by the backend (PRD §10 state machine). UI styles/translates each. */
export type ReportStatus =
  | 'SUBMITTED'
  | 'TRIAGED'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'RESOLVED'
  | 'CLOSED'
  | 'REJECTED'
  | 'DUPLICATE'
  | string;

/** Request body for `POST /reports` (file a report). Mirrors `FileReportDto`. */
export interface FileReportRequest {
  /** The chosen issue category's public id (required). */
  categoryId: string;
  /** Short title (required, ≤ 200). */
  title: string;
  /** Free-text description (required, ≤ 4000). */
  description: string;
  /** Resolved ward public id (required; minimum pin granularity, PRD §9.0). */
  wardId: string;
  /** Optional incident latitude (WGS84, −90..90); paired with {@link longitude}. */
  latitude?: number | null;
  /** Optional incident longitude (WGS84, −180..180); paired with {@link latitude}. */
  longitude?: number | null;
  /** Optional visibility preference (`PUBLIC`/`PRIVATE`); a sensitive category may override to PRIVATE. */
  visibility?: string | null;
  /** `true` to file without identity linkage (only honoured for sensitive categories). */
  anonymous: boolean;
  /** Optional object-store references to attachments (not used in this slice; reserved). */
  attachmentRefs?: string[];
}

/** Full owner/reporter view of a report (`ReportDto`) — returned on file + track-my-reports. */
export interface Report {
  /** The report's public id. */
  id: string;
  /** Human ticket code (`TAR-YYYY-NNNNNN`); also the anonymous tracking handle. */
  code: string;
  /** Issue category public id. */
  categoryId: string;
  /** Issue category display name. */
  categoryName: string;
  /** The title. */
  title: string;
  /** The description. */
  description: string;
  /** Resolved ward public id. */
  wardId: string;
  /** Constituency public id in effect, or null. */
  constituencyId: string | null;
  /** Incident latitude, or null. */
  latitude: number | null;
  /** Incident longitude, or null. */
  longitude: number | null;
  /** Effective visibility name. */
  visibility: string;
  /** Lifecycle status name. */
  status: ReportStatus;
  /** Priority name. */
  priority: string;
  /** SLA due instant (ISO-8601), or null. */
  dueAt: string | null;
  /** Resolution note, or null if unresolved. */
  resolution: string | null;
  /** Citizen confirmation outcome: null pending, true/false. */
  confirmation: boolean | null;
  /** Canonical report public id if a duplicate, else null. */
  duplicateOfId: string | null;
  /** Discovery-reach upvote count. */
  upvotes: number;
  /** Discovery-reach follower count. */
  followers: number;
  /** True if filed without identity linkage. */
  anonymous: boolean;
  /** Media attachment public ids (bytes fetched access-controlled from the media module). */
  attachmentRefs: string[];
  /** Filed instant (ISO-8601 UTC). */
  createdAt: string;
}

/** Public, PII-free report projection (`PublicReportDto`) for the feed/near-me list. */
export interface PublicReport {
  /** The report's public id. */
  id: string;
  /** Human ticket code (safe to show). */
  code: string;
  /** Issue category public id. */
  categoryId: string;
  /** Issue category display name. */
  categoryName: string;
  /** The title. */
  title: string;
  /** Ward public id (the public locator; no precise point). */
  wardId: string;
  /** Lifecycle status name. */
  status: ReportStatus;
  /** Priority name. */
  priority: string;
  /** Upvote count. */
  upvotes: number;
  /** Follower count. */
  followers: number;
  /** Filed instant (ISO-8601 UTC). */
  createdAt: string;
}

/** A single case-timeline entry (`CaseEventDto`) — the status history shown on track-my-reports. */
export interface CaseEvent {
  /** The event's public id. */
  id: string;
  /** The event type name (e.g. `STATUS_CHANGED`, `COMMENT_ADDED`). */
  eventType: string;
  /** True if a public event, false if internal-only. */
  publicEvent: boolean;
  /** Acting profile public id, or null for system/anonymous. */
  actorProfileId: string | null;
  /** The event body/description. */
  message: string;
  /** Event instant (ISO-8601 UTC). */
  createdAt: string;
}

/** An issue category (`IssueCategoryDto`) — drives the report category picker. */
export interface IssueCategory {
  /** The category's public id. */
  id: string;
  /** Stable machine code. */
  code: string;
  /** Swahili-first display name. */
  name: string;
  /** Parent category public id, or null for a top-level node. */
  parentId: string | null;
  /** Default routing level name. */
  defaultRoutingLevel: string;
  /** Default time-to-first-response SLA in minutes. */
  defaultSlaTtfrMinutes: number;
  /** Default time-to-resolution SLA in minutes. */
  defaultSlaTtrMinutes: number;
  /** True if anonymity-eligible (sensitive). */
  sensitive: boolean;
  /** True if reports here are forced PRIVATE. */
  forcePrivate: boolean;
  /** Default visibility name. */
  defaultVisibility: string;
  /** Optional UI icon token. */
  icon: string | null;
  /** True if shown in the picker. */
  active: boolean;
}
