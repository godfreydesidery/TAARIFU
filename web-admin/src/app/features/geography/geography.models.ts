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

/**
 * Lean ward (Kata) projection for the manual ward picker — mirrors the backend `WardSummaryDto`
 * (`GET /wards?q=`, `GET /districts/{id}/wards`; PRD §9.0, §22.6).
 *
 * <p>This is the smallest shape a picker needs to render a ward choice when GPS is unavailable: the ward's
 * public {@link id} (the value report/profile/responder forms take by hand), its display {@link name} and
 * {@link code}, plus the parent {@link councilName} and {@link districtName} so a picker can disambiguate
 * same-named wards (e.g. two "Mji Mpya" wards in different councils) without a second round-trip. Carries
 * no PII — only public reference data, safe for the unauthenticated reads it backs.</p>
 */
export interface WardSummary {
  /** The ward's public id (UUID) — the value clients pass when pinning a location/area. */
  id: string;
  /** Official ward code. */
  code: string;
  /** Ward display name (e.g. "Mengwe"). */
  name: string;
  /** Parent Council/LGA (Halmashauri) name, or `null` if unresolved. */
  councilName: string | null;
  /** District (Wilaya) ancestor name, or `null` if unresolved. */
  districtName: string | null;
}
