/**
 * Moderation DTOs — mirror the backend `ModerationItemDto`, `ModerationActionDto`, `AppealDto` and their
 * request bodies (moderation module; PRD §18, §25.8; US-12.x, UC-H01/H02/H03). These shape the flag
 * queue, take-action, and appeal-decision surfaces.
 */

/** Queue status tokens (mirror the backend `ModerationItemStatus`). */
export const MODERATION_STATUSES = ['PENDING', 'IN_REVIEW', 'ACTIONED', 'DISMISSED'] as const;

/** Action-type tokens (mirror `ModerationActionType`). */
export const MODERATION_ACTION_TYPES = ['DISMISS', 'WARN', 'HIDE', 'REMOVE', 'SUSPEND_AUTHOR', 'ESCALATE'] as const;

/** Severity tokens (mirror `ModerationSeverity`). */
export const MODERATION_SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;

/** Appeal-outcome tokens (mirror `AppealStatus`). */
export const APPEAL_OUTCOMES = ['UPHELD', 'OVERTURNED'] as const;

/** A moderation queue item (`GET /moderation/items`). One per flagged subject. */
export interface ModerationItem {
  /** The item's public id (UUID). */
  id: string;
  /** The flagged subject's type token (e.g. `REPORT`, `COMMENT`, `QUESTION`). */
  subjectType: string;
  /** The flagged subject's public id. */
  subjectId: string;
  /** Severity token. */
  severity: string;
  /** Queue status token. */
  status: string;
  /** Number of flags raised against the subject. */
  flagCount: number;
  /** Assigned moderator public id, or `null`. */
  assignedTo: string | null;
  /** SLA due instant (ISO-8601), or `null`. */
  slaDueAt: string | null;
  /** Queued-at instant (ISO-8601). */
  createdAt: string;
}

/** A recorded moderation action (`POST /moderation/items/{id}/actions`). */
export interface ModerationAction {
  /** The action's public id (UUID). */
  id: string;
  /** The queue item it was taken on. */
  itemId: string;
  /** Action-type token. */
  type: string;
  /** Stable reason code. */
  reasonCode: string;
  /** Taken-at instant (ISO-8601). */
  takenAt: string;
}

/** An appeal against a moderation action (`POST /moderation/appeals/{id}/decision`). */
export interface Appeal {
  /** The appeal's public id (UUID). */
  id: string;
  /** The appealed action's public id. */
  actionId: string;
  /** Appeal status/outcome token. */
  status: string;
  /** Decided-at instant (ISO-8601), or `null` if open. */
  decidedAt: string | null;
  /** Filed-at instant (ISO-8601). */
  createdAt: string;
}

/** Body for `POST /moderation/items/{id}/actions`. */
export interface TakeActionRequest {
  /** Action-type token. */
  type: string;
  /** Stable reason code (required). */
  reasonCode: string;
  /** Optional free-text note. */
  note?: string;
}

/** Body for `POST /moderation/appeals/{id}/decision`. */
export interface DecideAppealRequest {
  /** The appeal outcome token (UPHELD/OVERTURNED). */
  outcome: string;
  /** Optional decision note. */
  decisionNote?: string;
}
