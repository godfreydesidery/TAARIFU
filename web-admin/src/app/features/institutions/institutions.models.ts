/**
 * Institutions DTOs — mirror the backend `RepresentativeSummaryDto` and `PoliticalPartyDto`
 * (institutions module; PRD §9.1, §22.6). Wire shapes for the public party + representative directory.
 */

/**
 * A representative summary row (Mbunge/Diwani/ward executive) for the directory list.
 * `GET /representatives`. Nullable fields reflect that a rep may not yet be mapped to a
 * constituency/ward or party.
 */
export interface RepresentativeSummary {
  /** The representative's public id (UUID). */
  id: string;
  /** The backing profile's public id. */
  profileId: string;
  /** Type token (e.g. `MP`, `COUNCILLOR`, `WARD_EXEC`). */
  type: string;
  /** Mandate descriptor, if present. */
  mandate: string | null;
  /** Status token (e.g. `SITTING`, `FORMER`, `PENDING_VERIFICATION`). */
  status: string;
  /** Party display name, or `null` if independent/unmapped. */
  partyName: string | null;
  /** Party abbreviation, or `null`. */
  partyAbbrev: string | null;
  /** Constituency (Jimbo) public id, or `null`. */
  constituencyId: string | null;
  /** Constituency name, or `null`. */
  constituencyName: string | null;
  /** Ward (Kata) public id, or `null`. */
  wardId: string | null;
  /** Ward name, or `null`. */
  wardName: string | null;
  /** Legislature descriptor, or `null`. */
  legislature: string | null;
}

/** A political party (Chama) directory row. `GET /parties`. */
export interface PoliticalParty {
  /** The party's public id (UUID). */
  id: string;
  /** Stable machine code. */
  code: string;
  /** Display name. */
  name: string;
  /** Abbreviation (e.g. "CCM"). */
  abbreviation: string | null;
  /** Stated ideology, or `null`. */
  ideology: string | null;
  /** Year founded, or `null`. */
  foundedYear: number | null;
  /** Logo reference, or `null`. */
  logoRef: string | null;
  /** Status token (e.g. `REGISTERED`). */
  status: string | null;
  /** Free-text contacts blob, or `null`. */
  contacts: string | null;
}
