/**
 * Issue-category DTOs — mirror the backend `IssueCategoryDto`, `CreateIssueCategoryDto`, and
 * `UpdateIssueCategoryDto` (reporting module; PRD §9.1, Appendix D). These shape the admin CRUD
 * (`/issue-categories/admin`, POST/PUT/DELETE `/issue-categories`).
 */

/** A reportable issue category (Aina ya Tatizo). Response shape for reads. */
export interface IssueCategory {
  /** The category's public id (UUID). */
  id: string;
  /** Stable machine code (UPPER_SNAKE_CASE, e.g. `WATER_SANITATION`). Immutable. */
  code: string;
  /** Swahili-first display name. */
  name: string;
  /** Parent category public id, or `null` for a top-level node. */
  parentId: string | null;
  /** Default routing-level token name (Appendix D.1). */
  defaultRoutingLevel: string;
  /** Default time-to-first-response, minutes. */
  defaultSlaTtfrMinutes: number;
  /** Default time-to-resolution, minutes. */
  defaultSlaTtrMinutes: number;
  /** Whether reports here are anonymity-eligible (D-Q1). */
  sensitive: boolean;
  /** Whether reports here are forced PRIVATE. */
  forcePrivate: boolean;
  /** Default visibility token name. */
  defaultVisibility: string;
  /** Optional UI icon token. */
  icon: string | null;
  /** Whether the category is shown in the citizen picker. */
  active: boolean;
}

/**
 * Create request body (`POST /issue-categories`). The `code` is set once and immutable thereafter;
 * field validation mirrors the server's Bean Validation so the form rejects bad input before the call.
 */
export interface CreateIssueCategory {
  /** UPPER_SNAKE_CASE code (immutable once set). */
  code: string;
  /** Display name. */
  name: string;
  /** Optional parent category public id. */
  parentId?: string | null;
  /** Routing-level token name (validated server-side). */
  defaultRoutingLevel: string;
  /** Default TTFR, minutes (≥ 1). */
  defaultSlaTtfrMinutes: number;
  /** Default TTR, minutes (≥ 1). */
  defaultSlaTtrMinutes: number;
  /** Sensitivity flag. */
  sensitive: boolean;
  /** Force-private flag. */
  forcePrivate: boolean;
  /** Visibility token name (validated server-side). */
  defaultVisibility: string;
  /** Optional UI icon token. */
  icon?: string | null;
}

/**
 * Update request body (`PUT /issue-categories/{id}`). `code` and `parent` are NOT editable (the machine
 * code is immutable — clients/imports match on it), and `active` toggles picker visibility.
 */
export interface UpdateIssueCategory {
  /** New display name. */
  name: string;
  /** New routing-level token name. */
  defaultRoutingLevel: string;
  /** New TTFR, minutes (≥ 1). */
  defaultSlaTtfrMinutes: number;
  /** New TTR, minutes (≥ 1). */
  defaultSlaTtrMinutes: number;
  /** New sensitivity flag. */
  sensitive: boolean;
  /** New force-private flag. */
  forcePrivate: boolean;
  /** New visibility token name. */
  defaultVisibility: string;
  /** New UI icon token. */
  icon?: string | null;
  /** New active flag (retire/restore in the picker). */
  active: boolean;
}
