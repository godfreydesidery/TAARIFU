/**
 * Auth domain types for the citizen PWA — mirror the backend auth DTOs (AuthController, AUTH-DESIGN §3/§5).
 *
 * <p>The citizen flow is **phone + OTP** (passwordless): request an OTP, then verify it to either complete
 * signup (new account → T1) or log in (existing account). Per the locked decision (one account / additive
 * roles) there is no separate "register vs login" identity — the same phone resolves to the same account.
 * The tier/role decoded from the JWT are a UI HINT ONLY — the server re-checks every protected action.</p>
 */

/** Body for `POST /auth/otp/request` (signup) and `POST /auth/login/otp/request` (login). */
export interface OtpRequest {
  /** Destination phone in E.164 (e.g. `+255700000001`). Validated client-side; never logged. */
  phone: string;
}

/** Response from an OTP request — the challenge id to verify against (carries no PII, anti-enumeration). */
export interface OtpChallenge {
  /** The OTP challenge public id, supplied back on verify. */
  challengeId: string;
}

/** Body for `POST /auth/signup` and `POST /auth/login/otp` — verifies a challenge with the SMS code. */
export interface VerifyOtpRequest {
  /** The challenge id returned by the request step. */
  challengeId: string;
  /** The numeric OTP the citizen received by SMS (never logged). */
  code: string;
}

/** The access + refresh JWT pair returned by signup/login/refresh (`TokenPairDto`). */
export interface TokenPair {
  /** Short-lived (~15 min) access JWT attached as `Authorization: Bearer`. */
  accessToken: string;
  /** Rotating, single-use refresh JWT (~30 days) exchanged at `/auth/refresh`. */
  refreshToken: string;
}

/** Result of a successful signup (`AuthResultDto`): account id, server tier hint, and the token pair. */
export interface AuthResult {
  /** The authenticated account's public id (UUID). */
  userPublicId: string;
  /** Server-computed trust tier name (UI hint; gating re-resolves live). */
  tier: string;
  /** The issued access + refresh pair. */
  tokens: TokenPair;
}

/**
 * Result of a first-factor login (`LoginResultDto`). For a citizen (no staff MFA) `tokens` is present and
 * `mfaRequired` is false. Staff/MFA accounts return `mfaRequired=true` with an `mfaToken` — the citizen
 * PWA does not drive the TOTP step; it surfaces a clear "use the staff console" message instead.
 */
export interface LoginResult {
  /** Whether a staff TOTP second factor is required (not applicable to citizen accounts). */
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
 * <p>UI gating (showing a tier-unlock prompt, enabling "file a report") reads {@link roles} and
 * {@link tier} from here. This is convenience only: the backend's method-level `@PreAuthorize` +
 * `@RequiresTier` is the real gate. Tampering with these claims client-side cannot grant a real
 * privilege.</p>
 */
export interface Session {
  /** The account's public id (UUID) from the `sub` claim. */
  userId: string;
  /** Display name/subject, if the token carries one. */
  name?: string;
  /** Granted role names (e.g. `CITIZEN`, `REPRESENTATIVE`) — UI gating hint only. */
  roles: string[];
  /** Trust tier (e.g. `T1`–`T3`) — UI gating hint only. */
  tier?: string;
  /** Access-token expiry as epoch seconds (`exp` claim). */
  exp?: number;
}
