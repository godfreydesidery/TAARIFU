/**
 * Geography DTOs — mirror the backend `RegionDto` and `DistrictDto` (geography module; PRD §9.0).
 *
 * <p>These are the wire shapes for the public, read-only geography endpoints consumed by the admin
 * console. Ids are public UUIDs (never internal numeric ids — ADR-0006).</p>
 */

/** A Region (Mkoa) — top of the administrative hierarchy; has no parent. `GET /regions`. */
export interface Region {
  /** The region's public id (UUID). */
  id: string;
  /** Official region code. */
  code: string;
  /** Display name (e.g. "Kilimanjaro"). */
  name: string;
}

/** A District (Wilaya) within a region. `GET /regions/{regionId}/districts`. */
export interface District {
  /** The district's public id (UUID). */
  id: string;
  /** Official district code. */
  code: string;
  /** Display name (e.g. "Rombo"). */
  name: string;
  /** The parent region's public id. */
  regionId: string;
}
