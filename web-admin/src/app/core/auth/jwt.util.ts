import { Session } from './auth.models';

/**
 * Minimal, dependency-free JWT claim decoder.
 *
 * <p>Responsibility: read the (non-secret) claims of an access token for UI gating ONLY — never to make
 * a security decision (ARCHITECTURE.md §6.2: the server is the real gate). Decoding only base64url-parses
 * the payload; it does NOT verify the signature (the client can't, and doesn't need to — it trusts the
 * server's enforcement). Written by hand rather than pulling a JWT library to keep the bundle small for
 * low-data/low-end-device clients (PRD §15).</p>
 *
 * <p>WHY tolerant parsing: a malformed/legacy token must degrade to "no session" rather than crash the
 * app, so every step is guarded and returns `null` on failure.</p>
 */

/**
 * Decodes the JWT payload (claims) without verifying the signature.
 * @param token the JWT (header.payload.signature).
 * @returns the claims object, or `null` if the token is malformed.
 */
export function decodeJwtClaims(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) {
      return null;
    }
    // base64url → base64, then decode and parse UTF-8 safely.
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join(''),
    );
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/**
 * Builds the UI {@link Session} from an access token's claims.
 *
 * <p>Reads common claim shapes defensively (the backend may carry roles as `roles`, `authorities`, or a
 * space-delimited `scope`; tier as `tier`). Returns `null` for an unparseable token so callers treat the
 * user as logged out.</p>
 *
 * @param token the access JWT.
 * @returns the decoded {@link Session}, or `null` if the token can't be read.
 */
export function sessionFromToken(token: string): Session | null {
  const claims = decodeJwtClaims(token);
  if (!claims) {
    return null;
  }
  return {
    userId: asString(claims['sub']) ?? '',
    name: asString(claims['name']) ?? asString(claims['preferred_username']),
    roles: extractRoles(claims),
    tier: asString(claims['trustTier']) ?? asString(claims['tier']),
    exp: typeof claims['exp'] === 'number' ? (claims['exp'] as number) : undefined,
  };
}

/**
 * @param token the access JWT.
 * @returns true if the token's `exp` is in the past (with a small clock-skew margin).
 */
export function isTokenExpired(token: string): boolean {
  const claims = decodeJwtClaims(token);
  const exp = claims && typeof claims['exp'] === 'number' ? (claims['exp'] as number) : undefined;
  if (exp === undefined) {
    return false; // No exp claim → let the server decide; don't force a refresh loop.
  }
  const nowSeconds = Math.floor(Date.now() / 1000);
  const skewSeconds = 10;
  return exp <= nowSeconds + skewSeconds;
}

/** Normalises the various role-claim shapes the backend might emit into a `string[]`. */
function extractRoles(claims: Record<string, unknown>): string[] {
  const roles = claims['roles'] ?? claims['authorities'];
  if (Array.isArray(roles)) {
    return roles.map(String).map(stripRolePrefix);
  }
  const scope = asString(claims['scope']);
  if (scope) {
    return scope.split(/\s+/).map(stripRolePrefix);
  }
  return [];
}

/** Strips a leading `ROLE_` so UI checks compare on bare role names (e.g. `ADMIN`). */
function stripRolePrefix(role: string): string {
  return role.startsWith('ROLE_') ? role.slice('ROLE_'.length) : role;
}

/** @returns the value as a string when it is one, else `undefined`. */
function asString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}
