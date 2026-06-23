import { isTokenExpired, sessionFromToken } from './jwt.util';

/** Builds an unsigned JWT (header.payload.) with the given claims for testing the decoder. */
function makeToken(claims: Record<string, unknown>): string {
  const b64 = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none', typ: 'JWT' })}.${b64(claims)}.`;
}

describe('jwt.util', () => {
  it('decodes sub, roles, and tier into a Session', () => {
    const token = makeToken({ sub: 'u-123', roles: ['ROLE_ADMIN', 'CITIZEN'], tier: 'T3' });
    const session = sessionFromToken(token);
    expect(session?.userId).toBe('u-123');
    // ROLE_ prefix is stripped so UI checks compare bare names.
    expect(session?.roles).toEqual(['ADMIN', 'CITIZEN']);
    expect(session?.tier).toBe('T3');
  });

  it('returns null for a malformed token', () => {
    expect(sessionFromToken('not-a-jwt')).toBeNull();
  });

  it('treats a past exp as expired', () => {
    const past = Math.floor(Date.now() / 1000) - 60;
    expect(isTokenExpired(makeToken({ sub: 'u', exp: past }))).toBeTrue();
  });

  it('treats a far-future exp as not expired', () => {
    const future = Math.floor(Date.now() / 1000) + 3600;
    expect(isTokenExpired(makeToken({ sub: 'u', exp: future }))).toBeFalse();
  });
});
