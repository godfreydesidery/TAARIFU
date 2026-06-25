/**
 * Feed / discovery types for the citizen PWA — mirror the backend comms/engagement/search DTOs
 * (AnnouncementDto, PetitionDto, SearchResultDto). PRD §11 (announcements), §12 (engagement), discovery.
 */

/** A published announcement (`AnnouncementDto`). The body is bilingual (SW + EN) per the i18n mandate. */
export interface Announcement {
  /** Public id. */
  id: string;
  /** Author profile public id. */
  authorId: string;
  /** Title (single-language as authored). */
  title: string;
  /** Swahili body. */
  bodySw: string;
  /** English body. */
  bodyEn: string;
  /** Optional issue-category public id, or null. */
  categoryId: string | null;
  /** Intended audience role token. */
  audienceRole: string;
  /** Status (e.g. `PUBLISHED`). */
  status: string;
  /** True if held by moderation. */
  moderationHeld: boolean;
  /** Publish instant (ISO-8601), or null. */
  publishAt: string | null;
  /** Expiry instant (ISO-8601), or null. */
  expireAt: string | null;
}

/** A petition (`PetitionDto`) surfaced in the engagement feed. */
export interface Petition {
  /** Public id. */
  id: string;
  /** Title. */
  title: string;
  /** Body text. */
  body: string;
  /** Target type token (e.g. `REPRESENTATIVE`). */
  targetType: string;
  /** Target public id, or null. */
  targetId: string | null;
  /** Signature goal. */
  signatureGoal: number;
  /** Current signature count. */
  signatureCount: number;
  /** Deadline instant (ISO-8601), or null. */
  deadline: string | null;
  /** Creator profile public id, or null. */
  creatorProfileId: string | null;
  /** Creator org public id, or null. */
  creatorOrgId: string | null;
  /** Status (e.g. `OPEN`). */
  status: string;
  /** Official response text, or null. */
  response: string | null;
}

/** Entity types a search result can represent (mirrors backend `SearchEntityType`). */
export type SearchEntityType =
  | 'REPRESENTATIVE'
  | 'ORGANISATION'
  | 'ANNOUNCEMENT'
  | 'ISSUE_CATEGORY'
  | 'PUBLIC_REPORT'
  | string;

/** A ranked cross-entity search hit (`SearchResultDto`). */
export interface SearchResult {
  /** The kind of entity this hit represents. */
  entityType: SearchEntityType;
  /** The matched entity's public id. */
  entityPublicId: string;
  /** Display title. */
  title: string;
  /** A short matched snippet. */
  snippet: string | null;
  /** Area (ward-or-coarser) public id, or null. */
  areaId: string | null;
  /** Issue-category public id, or null. */
  categoryId: string | null;
}
