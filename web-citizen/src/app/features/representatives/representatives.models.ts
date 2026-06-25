/**
 * Representative directory types for the citizen PWA — mirror the backend institutions DTOs
 * (RepresentativeSummaryDto, RepresentativeDto, MyRepresentativesDto). PRD §8 (find-my-rep).
 */

/** A representative summary row (`RepresentativeSummaryDto`) for the directory list. */
export interface RepresentativeSummary {
  /** Public id. */
  id: string;
  /** Linked profile public id. */
  profileId: string;
  /** Type token (e.g. `MP` / `COUNCILLOR`) — translated/iconised in the UI. */
  type: string;
  /** Mandate descriptor. */
  mandate: string;
  /** Status (e.g. `ACTIVE`). */
  status: string;
  /** Party full name, or null. */
  partyName: string | null;
  /** Party abbreviation (e.g. `CCM`), or null. */
  partyAbbrev: string | null;
  /** Constituency (Jimbo) public id, or null. */
  constituencyId: string | null;
  /** Constituency name, or null. */
  constituencyName: string | null;
  /** Ward (Kata) public id, or null. */
  wardId: string | null;
  /** Ward name, or null. */
  wardName: string | null;
  /** Legislature descriptor, or null. */
  legislature: string | null;
}

/**
 * The "my representatives" bundle (`MyRepresentativesDto`) resolved from a ward — the MP (Mbunge) for the
 * ward's constituency (Jimbo) and the Councillor (Diwani) for the ward (Kata). PRD §8.
 */
export interface MyRepresentatives {
  /** The resolved ward public id. */
  wardId: string;
  /** The ward (Kata) name. */
  wardName: string;
  /** The constituency (Jimbo) public id, or null. */
  constituencyId: string | null;
  /** The constituency name, or null. */
  constituencyName: string | null;
  /** The Member of Parliament (Mbunge) for the constituency, or null if unmapped. */
  mp: RepresentativeSummary | null;
  /** The Councillor (Diwani) for the ward, or null if unmapped. */
  councillor: RepresentativeSummary | null;
}
