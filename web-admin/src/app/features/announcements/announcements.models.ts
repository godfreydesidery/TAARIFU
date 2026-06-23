/**
 * Announcement DTOs — mirror the backend `AnnouncementDto` (communications module; PRD §12, §22.6, M4).
 *
 * <p>These are the wire shapes for the public announcement-detail read consumed by the admin console.
 * Ids are public UUIDs (never internal numeric ids — ADR-0006). Both language bodies are returned so the
 * client renders the recipient's locale (SW-first, EN fallback — ADR-0010).</p>
 */

/**
 * A published, citizen-visible announcement (`GET /announcements/{id}`).
 *
 * <p>WHY both bodies + all metadata: the detail view shows the full headline, the locale-appropriate body,
 * the author/category references, the targeted areas, delivery channels, and the publish/expiry window so a
 * staff reviewer sees exactly what a citizen sees. The endpoint only ever returns PUBLISHED, in-window
 * announcements — drafts/scheduled/expired/held/deleted 404 (never leaked, PRD §18) — so a successful read
 * is always safe to display publicly.</p>
 */
export interface Announcement {
  /** The announcement's public id (UUID). */
  id: string;
  /** The authoring profile's public id. */
  authorId: string;
  /** The headline. */
  title: string;
  /** The Swahili body (always present — SW-first). */
  bodySw: string;
  /** The English body, or `null`. */
  bodyEn: string | null;
  /** The tagged issue-category public id, or `null`. */
  categoryId: string | null;
  /** The role-name audience narrowing (e.g. `CITIZEN`), or `null` for everyone. */
  audienceRole: string | null;
  /** The lifecycle status name (DRAFT/SCHEDULED/PUBLISHED/EXPIRED). */
  status: string;
  /** Whether held for moderation (blocks publish until cleared). */
  moderationHeld: boolean;
  /** The targeted geo-area public ids. */
  areaIds: string[];
  /** The delivery-channel names (e.g. `PUSH`, `SMS`, `IN_APP`). */
  channels: string[];
  /** Attachment object-store keys. */
  attachmentRefs: string[];
  /** When it goes/went live (ISO-8601 UTC), or `null`. */
  publishAt: string | null;
  /** When it stops showing (ISO-8601 UTC), or `null`. */
  expireAt: string | null;
}
