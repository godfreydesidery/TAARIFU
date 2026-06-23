/**
 * Institutions ADMIN DTOs — mirror the backend `ParliamentDto`/`ParliamentWriteDto`,
 * `ParliamentRoleDto`/`ParliamentRoleWriteDto`, and `RepresentativeDto`/`RepresentativeWriteDto`
 * (institutions module; PRD §9.1; UC-B12/B13, UC-C04/C08). These shape the parliament + parliament-role
 * CRUD and the representative create/link form (the additive-role flow §6.4 D12).
 */

/** Legislature tokens (mirror the backend enum; validated server-side). */
export const LEGISLATURES = ['UNION_PARLIAMENT', 'ZANZIBAR_HOR'] as const;

/** Representative type tokens. */
export const REPRESENTATIVE_TYPES = ['MP', 'COUNCILLOR', 'WARD_EXEC'] as const;

/** Representative mandate tokens. */
export const REPRESENTATIVE_MANDATES = ['CONSTITUENCY', 'SPECIAL_SEATS', 'NOMINATED', 'COUNCILLOR_WARD'] as const;

/** Representative status tokens (role lifecycle, §6.4). */
export const REPRESENTATIVE_STATUSES = ['PENDING_VERIFICATION', 'SITTING', 'FORMER'] as const;

/** A parliament term (`GET /parliaments`). */
export interface Parliament {
  /** The term's public id (UUID). */
  id: string;
  /** Term number (e.g. 12). */
  termNumber: number | null;
  /** Display name. */
  name: string;
  /** Legislature token, or `null`. */
  legislature: string | null;
  /** Term start date (ISO date). */
  startDate: string | null;
  /** Term end date (ISO date), or `null` if current/open. */
  endDate: string | null;
  /** Whether this is the current term. */
  current: boolean;
}

/** Write body for `POST`/`PUT /admin/institutions/parliaments`. */
export interface ParliamentWrite {
  /** Term number. */
  termNumber: number;
  /** Display name. */
  name: string;
  /** Optional legislature token. */
  legislature?: string;
  /** Term start date (ISO date). */
  startDate: string;
  /** Optional term end date (ISO date). */
  endDate?: string | null;
  /** Whether this is the current term. */
  current: boolean;
}

/** A parliament role (`GET /parliaments/roles`), e.g. Speaker, Minister. */
export interface ParliamentRole {
  /** The role's public id (UUID). */
  id: string;
  /** Stable machine code (immutable). */
  code: string;
  /** Display name. */
  name: string;
  /** Optional description, or `null`. */
  description: string | null;
}

/** Write body for `POST`/`PUT /admin/institutions/parliament-roles`. */
export interface ParliamentRoleWrite {
  /** Stable code (immutable after create). */
  code: string;
  /** Display name. */
  name: string;
  /** Optional description. */
  description?: string;
}

/**
 * Representative create/link write body for `POST`/`PUT /admin/institutions/representatives`.
 *
 * <p>WHY `profileId` (not a new account): per the additive-role rule (§6.4 D12), an elected citizen keeps
 * ONE account — the admin links the REPRESENTATIVE role to their EXISTING profile, never re-registers
 * them. `constituencyId`/`wardId` are nullable for special-seats/nominated mandates.</p>
 */
export interface RepresentativeWrite {
  /** The EXISTING profile to link the representative role to (additive role, §6.4). */
  profileId?: string | null;
  /** Type token (MP/COUNCILLOR/WARD_EXEC). */
  type: string;
  /** Mandate token. */
  mandate: string;
  /** Constituency public id (nullable for special-seats/nominated). */
  constituencyId?: string | null;
  /** Ward public id (nullable). */
  wardId?: string | null;
  /** Party public id, or `null` (independent). */
  partyId?: string | null;
  /** Legislature token, or omit. */
  legislature?: string;
  /** Parliament term public id, or `null`. */
  parliamentId?: string | null;
  /** Parliament role public id, or `null`. */
  parliamentRoleId?: string | null;
  /** Status token. */
  status?: string;
  /** Elected-at date (ISO date), or `null`. */
  electedAt?: string | null;
  /** Free-text bio, or `null`. */
  bio?: string | null;
}
