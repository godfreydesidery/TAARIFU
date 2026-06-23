/**
 * Auth domain types — mirror the backend auth DTOs (AUTH-DESIGN §3/§5; AuthController).
 *
 * <p>These shape the request/response bodies for `/auth/login/password` and `/auth/refresh`, plus the
 * client-side decoded session derived from the JWT. Per ARCHITECTURE.md §6.2 the tier/role decoded
 * here are a UI HINT ONLY — the server re-checks every protected action; the client never trusts its
 * own claim for a security decision.</p>
 */

/** Body for `POST /auth/login/password`. `accountKey` is a phone or email. */
export interface PasswordLoginRequest {
  /** Account key — phone number or email (the backend resolves either). */
  accountKey: string;
  /** The account password. Sent over TLS; never logged or persisted. */
  password: string;
}

/** The access + refresh JWT pair returned by login/refresh (`TokenPairDto`). */
export interface TokenPair {
  /** Short-lived (~15 min) access JWT attached as `Authorization: Bearer`. */
  accessToken: string;
  /** Rotating, single-use refresh JWT (~30 days) exchanged at `/auth/refresh`. */
  refreshToken: string;
}

/**
 * The first-factor login result (`LoginResultDto`). For an admin/staff account with MFA enabled the
 * server returns `mfaRequired=true` and an `mfaToken` instead of a `tokens` pair — the TOTP step at
 * `/auth/login/totp` must follow. For a non-MFA account `tokens` is present.
 */
export interface LoginResult {
  /** Whether the staff TOTP second factor must be completed before access is granted. */
  mfaRequired: boolean;
  /** The issued token pair, or `null` when {@link mfaRequired} is true. */
  tokens: TokenPair | null;
  /** The MFA challenge token, or `null` when {@link tokens} are issued. */
  mfaToken: string | null;
}

/** Body for `POST /auth/refresh`. */
export interface RefreshRequest {
  /** The current refresh token to rotate. */
  refreshToken: string;
}

/**
 * The decoded, UI-facing session — derived from the access JWT's claims plus the stored tokens.
 *
 * <p>UI gating (showing/hiding admin nav, enabling a CRUD button) reads {@link roles} and {@link tier}
 * from here. This is convenience only: the backend's method-level `@PreAuthorize` is the real gate
 * (ARCHITECTURE.md §6.2). Tampering with these claims client-side cannot grant a real privilege.</p>
 */
export interface Session {
  /** The account's public id (UUID) from the `sub` claim. */
  userId: string;
  /** Display name/subject, if the token carries one. */
  name?: string;
  /** Granted role names (e.g. `ADMIN`, `MODERATOR`, `CITIZEN`) — UI gating hint only. */
  roles: string[];
  /** Trust tier (e.g. `T0`–`T3`) — UI gating hint only. */
  tier?: string;
  /** Access-token expiry as epoch seconds (`exp` claim), used to schedule pre-emptive refresh. */
  exp?: number;
}
