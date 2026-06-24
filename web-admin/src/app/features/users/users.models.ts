/**
 * Identity/role DTOs for the Users & Roles admin area (M14, US-14.1, UC-H06) — mirror the backend admin
 * user-management contract: `UserAdminSummary` (list row), `UserAdminDetail` + `UserRoleGrant` (detail),
 * `GrantRoleRequest`/`RoleGrantedDto`/`SuspendUserRequest` (mutations), and the operator's own `MeDto`
 * snapshot (`GET /profiles/me`). PRD §6.4, §7.1, §18.
 *
 * <p>PII discipline (PRD §18, PDPA): the list/detail carry only a <b>masked</b> phone and never a national/
 * voter ID — these shapes intentionally have no field for a raw phone or `idNo`, so the console cannot even
 * render one. The console acts only on the immutable `publicId`/`assignmentId` (ADR-0006).</p>
 */

/** Account lifecycle status tokens (mirror the backend account status enum). */
export const USER_STATUSES = ['PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED'] as const;

/** Trust-tier tokens (PRD §6 tiered identity). */
export const USER_TIERS = ['T0', 'T1', 'T2', 'T3'] as const;

/** Role-grant lifecycle status tokens (mirror `RoleAssignmentStatus`). */
export const GRANT_STATUSES = ['PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'FORMER'] as const;

/**
 * The platform role catalogue (PRD §7.2). Backs the role filter + the grant-role picker. `CITIZEN` is the
 * implicit self-sign-up base role and is never granted via the admin flow, so it is excluded from the
 * grant picker (see {@link GRANTABLE_ROLES}).
 */
export const ROLE_CATALOGUE = [
  'CITIZEN',
  'ORGANIZATION_MEMBER',
  'ORGANIZATION_ADMIN',
  'REPRESENTATIVE',
  'AREA_OFFICIAL',
  'MODERATOR',
  'ADMIN',
  'ROOT',
] as const;

/**
 * Roles an admin may additively grant to an existing account (D15). Excludes `CITIZEN` (the implicit base
 * role every account already holds) and `ROOT` (bootstrap-only; never granted from the console).
 */
export const GRANTABLE_ROLES = [
  'ORGANIZATION_MEMBER',
  'ORGANIZATION_ADMIN',
  'REPRESENTATIVE',
  'AREA_OFFICIAL',
  'MODERATOR',
  'ADMIN',
] as const;

/**
 * A privacy-minimised account row for the user-management list (`GET /admin/users`) — mirrors the backend
 * `UserAdminSummary`. Masked phone only; never a raw number or ID (PRD §18).
 */
export interface UserAdminSummary {
  /** The account's immutable public id (the only id admin commands reference). */
  publicId: string;
  /** Profile display name (first + last, or org name), or `null` if the profile is incomplete. */
  displayName: string | null;
  /** Account phone with middle digits masked (e.g. `+2557****1234`); never the raw number. */
  maskedPhone: string | null;
  /** Cached trust-tier name (T0–T3). */
  trustTier: string;
  /** Account lifecycle status name. */
  status: string;
  /** Names of the account's currently active roles (additive — §6.4; at least `CITIZEN`). */
  roles: string[];
  /** Created-at instant (ISO-8601), for sorting/triage. */
  createdAt: string;
}

/**
 * One of an account's role grants — the role plus its attribute scope and effective window — for the
 * detail view (mirrors the backend `UserRoleGrant`).
 *
 * <p>The {@link assignmentId} is what {@code DELETE …/roles/{assignmentId}} targets — an account may hold
 * several grants of the same role with different scopes, so the role name alone cannot identify the one to
 * revoke. Scope ids cross as opaque UUIDs; an empty scope set means "unrestricted within that role".</p>
 */
export interface UserRoleGrant {
  /** The `RoleAssignment` public id (the id a revoke command targets). */
  assignmentId: string;
  /** The granted role catalogue name. */
  roleName: string;
  /** Grant lifecycle status name (`PENDING_VERIFICATION`/`ACTIVE`/`SUSPENDED`/`FORMER`). */
  status: string;
  /** Area-scope public ids the grant is limited to; empty = unrestricted by area. */
  areaIds: string[];
  /** Issue-category-scope public ids the grant is limited to; empty = unrestricted. */
  categoryIds: string[];
  /** Single constituency-scope public id, or `null` if not constituency-scoped. */
  constituencyId: string | null;
  /** When the grant takes effect (ISO-8601), or `null` = effective on creation. */
  effectiveFrom: string | null;
  /** When the grant ends (ISO-8601), or `null` = open-ended. */
  effectiveTo: string | null;
}

/**
 * One account's admin detail — roles + scopes, tier, status, location count (`GET /admin/users/{id}`);
 * mirrors the backend `UserAdminDetail`.
 *
 * <p>PII discipline: the phone is masked; the national/voter `idNo` is NEVER returned. {@link locationCount}
 * is a bare count — the pinned `ProfileLocation`s (private PII) are not listed; the operator sees only that
 * the account has N pins, never where they are. {@link idVerified} surfaces T3 verification state without
 * revealing any ID value (PRD §18, PDPA).</p>
 */
export interface UserAdminDetail {
  /** The account's immutable public id. */
  publicId: string;
  /** Profile display name, or `null`. */
  displayName: string | null;
  /** Masked account phone; never the raw number. */
  maskedPhone: string | null;
  /** Optional login-alias email, or `null`. */
  email: string | null;
  /** Cached trust-tier name (T0–T3). */
  trustTier: string;
  /** Account lifecycle status name. */
  status: string;
  /** Whether the account's government ID is verified (T3 gate); no ID value leaks. */
  idVerified: boolean;
  /** The account's role grants with their scope + effective window (additive — §6.4). */
  roles: UserRoleGrant[];
  /** Number of pinned `ProfileLocation`s (count only, no PII). */
  locationCount: number;
  /** Created-at instant (ISO-8601). */
  createdAt: string;
  /** Last successful login instant (ISO-8601), or `null` if never logged in. */
  lastLoginAt: string | null;
}

/**
 * Request body for `POST /admin/users/{publicId}/roles` — grant a role additively with optional scope and
 * effective window (mirrors the backend `GrantRoleRequest`). The target account is the path id and the
 * acting admin is the authenticated caller — never a body field.
 */
export interface GrantRoleRequest {
  /** The role catalogue name to grant (validated against the catalogue server-side). */
  roleName: string;
  /** Area-scope public ids to limit the grant to, or omitted/empty = unrestricted. */
  areaIds?: string[];
  /** Issue-category-scope public ids to limit the grant to, or omitted/empty. */
  categoryIds?: string[];
  /** Single constituency-scope public id, or omitted if not constituency-scoped. */
  constituencyId?: string;
  /** When the grant takes effect (ISO-8601 UTC), or omitted = effective on creation. */
  effectiveFrom?: string;
  /** When the grant ends (ISO-8601 UTC), or omitted = open-ended. */
  effectiveTo?: string;
}

/** Response body of a successful role grant (`RoleGrantedDto`): the created/existing assignment's id. */
export interface RoleGranted {
  /** The granted (or existing active) `RoleAssignment` public id, addressable for a later revoke. */
  assignmentId: string;
}

/** Request body for `POST /admin/users/{publicId}/suspend` — optional machine reason code (never PII). */
export interface SuspendUserRequest {
  /** Optional machine reason for the suspension (e.g. `POLICY_VIOLATION`, `SECURITY`); ≤64 chars. */
  reasonCode?: string;
}

/** Filters for the user list (`GET /admin/users`); every field optional (`undefined` = no constraint). */
export interface UserListFilter {
  /** Name fragment filter. */
  name?: string;
  /** Trailing-phone-digits filter (never the full number). */
  phoneSuffix?: string;
  /** Trust-tier filter (T0–T3). */
  tier?: string;
  /** Role-name filter. */
  role?: string;
  /** Account-status filter. */
  status?: string;
  /** Zero-based page index. */
  page?: number;
  /** Page size (capped server-side). */
  size?: number;
}

/** The signed-in operator's own profile + role snapshot (`GET /profiles/me`). For the "my identity" card. */
export interface Me {
  /** The account's public id (UUID). */
  userId?: string;
  /** The profile's public id (UUID), when present. */
  profileId?: string;
  /** First name, or `null`. */
  firstName?: string | null;
  /** Last name, or `null`. */
  lastName?: string | null;
  /** Profile type token (PERSON/ORGANIZATION), or `null`. */
  type?: string | null;
  /** Live trust tier (T0–T3). */
  tier?: string | null;
  /** Granted role names (additive, §6.4). */
  roles?: string[];
  /** Whether the ID is verified. */
  idVerified?: boolean;
  /** Whether the email is verified. */
  emailVerified?: boolean;
  /** Whether the phone is verified. */
  phoneVerified?: boolean;
}
