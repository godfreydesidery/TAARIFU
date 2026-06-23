/**
 * Identity/role DTOs for the Users & Roles admin area — mirror the backend `MeDto` snapshot
 * (`GET /profiles/me`) and the known staff role catalogue (identity module; PRD §6.4, §7).
 *
 * <p>IMPORTANT (CENTRAL NEED): the backend exposes NO generic "list all users" or "grant/scope an
 * arbitrary role to a user" admin endpoint today — the only role-grant flow that exists is the additive
 * REPRESENTATIVE-link (US-0.6, §6.4 D12), and the only first-party identity read is the caller's OWN
 * `/profiles/me`. This area therefore surfaces the current operator's identity + roles and routes to the
 * existing grant flow, and explicitly flags the missing user/role-admin API. See CENTRAL NEEDS.</p>
 */

/** The signed-in operator's own profile + role snapshot (`GET /profiles/me`). */
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

/** The platform role catalogue (PRD §7.2). For display + the (future) grant picker. */
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
