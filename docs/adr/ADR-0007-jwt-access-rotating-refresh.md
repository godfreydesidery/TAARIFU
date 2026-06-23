# ADR-0007: Stateless JWT — short access + rotating refresh — with method-level RBAC, scopes & trust tiers

**Status:** Accepted · 2026-06-23 · Solution Architect
**Grounding:** PRD §17 (auth), §18 (security), §7 (RBAC + tiers T0–T3); CLAUDE.md §3, §12.

## Context
The backend is a stateless, horizontally scalable monolith serving mobile, web/PWA, admin, and USSD/SMS clients (PRD §16). Authorization must be **deny-by-default, method-level, scoped, and tiered**, enforced **server-side** — the legacy code left `@PreAuthorize` ineffective and admin surfaces "authenticated-only" (PRD §7.1, §18). Long-lived tokens that can't be revoked are a risk; sessions don't scale statelessly.

## Decision
- **Stateless JWT.** Short-lived **access token** (~15 min) + **rotating, single-use refresh token** (~30 days) with **reuse-detection → family revocation**. Refresh tokens persisted **hashed** (`identity.RefreshToken`) so they're revocable; access tokens not stored. Asymmetric signing (RS256/ES256) for key rotation.
- Login by **phone/email + password or OTP**; OTP/attempt counters in Redis with lockout/backoff; optional **TOTP MFA** for staff (PRD §18).
- **Authorization:** global `@EnableMethodSecurity`, deny-by-default, **`@PreAuthorize` on every protected endpoint**. **RBAC** over the PRD §7.2 role catalogue; **scoped roles** (`RoleAssignment.areaIds/categoryIds/constituencyId`) via a custom scope-checker; **trust tiers T0–T3** carried as a claim but **re-checked server-side** per action (`@RequiresTier`). Conflict-of-interest (D13/D16) and the **integrity fence** (binding actions never read token balance, D18/§23.5) are enforced here.
- **No secrets in source** — keys/creds from env/secret manager (PRD §18; CLAUDE.md §12).

## Consequences
- (+) Stateless horizontal scale; revocable refresh; least-privilege, server-authoritative authz that closes the legacy gaps.
- (+) Same token model works for an extracted service later (resource-server pattern — ARCHITECTURE §10).
- (−) Access tokens can't be revoked before expiry — bounded by the short TTL; sensitive state changes re-check tier/scope live.
- (−) Refresh rotation + reuse-detection adds storage and logic — accepted; it's the security win.
- **Revisit trigger:** if staff SSO is required, add the optional OIDC adapter (EI-15) mapping IdP groups→roles; the native path stays the fallback.
