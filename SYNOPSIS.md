# Taarifu Engine — Project Synopsis

*Prepared as the foundation for a clean rewrite (PRD → build). Based on a full read of the three repositories: `taarifu-engine-api`, `taarifu-engine-dash`, `taarifu-mob-app` (snapshots dated early October 2025).*

---

## 1. What Taarifu is

**Taarifu** ("taarifu" is Swahili for *report / inform / notify*) is intended to be a **citizen civic-engagement platform for Tanzania** — a system where verified citizens can engage with their political representatives and report issues, scoped to Tanzania's full administrative and political geography, administered through a web back office.

It is built as **three applications around one backend**:

| Application | Repo | Stack | Role |
|---|---|---|---|
| **Engine API** | `taarifu-engine-api` | Spring Boot 3.2 · Java 21 · Spring Security · JPA · JWT · (MySQL/H2) | The backend and single source of truth |
| **Engine Dashboard** | `taarifu-engine-dash` | Angular 18 · standalone components · Bootstrap 5.3 | Admin/back-office web app |
| **Mobile App** | `taarifu-mob-app` | Flutter · flutter_bloc · http | Citizen-facing mobile app |

The naming ("Engine") and a stub `/engine` controller hint at an ambition larger than CRUD, but no "engine" logic exists yet.

---

## 2. The central finding: a strong foundation, an unbuilt product

The single most important thing to understand before writing the PRD:

> **Taarifu today is a *reference-data catalog* (Tanzania geography + political organisations) with a working admin dashboard and an *auth-only* mobile shell. The civic-reporting product that the name, the user model, and the mobile sign-up all promise is essentially 0% built.**

The team has built the **context** of the platform — *where* things happen (Region → District → Ward → Village → Hamlet, plus Constituency) and *which* political bodies exist (Political Parties, Parliaments) — but none of the **content**: there is no Report, Issue, Complaint, Notification, Feed, Comment, Representative/MP, Election, or even a Citizen *profile* entity anywhere in the system.

This is the rewrite's defining gap, and it's good news: the hard, tedious reference data is largely done; the actual product is greenfield and can be designed cleanly.

---

## 3. Current state at a glance (what is real vs. scaffolded)

### Engine API — *functional reference-data backend*
**Implemented and working:**
- Admin JWT login (`POST /admin/v1/auth/login`): username-or-email lookup, BCrypt, ADMIN-type enforcement, access + refresh tokens (HS256).
- Full **CRUD + soft-delete + search + pagination + per-resource `/stats`** for **8 entities**: Region, District, Ward, Village, Hamlet, Constituency, Parliament, Political Party.
- A denormalized **Area** index (read-only API) auto-maintained across all location types.
- Consistent response envelopes, global exception handling, ULID public ids + human-readable codes (e.g. `RG0001`, `DT000001`), root-admin seeding on first run.

**Scaffolded / dead / misleading:**
- Citizen & organisation login (`authenticateUser(...)`) and `refreshToken()` / `logout()` exist in the service layer but **have no controller routes**.
- **No `/mob/v1` controllers at all** — the entire surface the mobile app calls is missing (see §5).
- Some "filter" endpoints (parties by ideology / founding year / operational status) **ignore their filter and return everything** ("for simplicity").
- `User` carries capability helpers (`canReportIssues()`, `canPerformAdminActions()`, …) and a `PasswordStrength` model that are **never called or computed**.

### Engine Dashboard — *near-production admin CRUD*
**Implemented:** login + JWT (localStorage) + auth guard + bearer interceptor; full list/create/edit/delete/toggle UI for all 8 entities with server-side pagination, sort, search; cascading parent dropdowns; read-only Area view; toast system; responsive layout (header + collapsible sidenav + footer); a stat-card dashboard.
**Stubbed:** Profile, Settings, Help/About, "Recent Activity," sidenav logout — all empty stubs. No role-awareness (the user's type is ignored). No refresh-token use. No tests.

### Mobile App — *auth-only prototype (~5–10%)*
**Implemented:** login, a **KYC-grade self-registration form** (full name, display name, email, phone, DOB with 13+ gate, gender, ID type [National ID / Passport / Licence], ID number, address, optional bio), and persisted session via `shared_preferences`.
**Everything else is absent or broken:** the home screen is a bare "Welcome to Taarifu!" card; there is **no reporting, feed, notifications, or any data fetch**; the auth service **fabricates dummy tokens** when the backend response doesn't match (because the real backend endpoint doesn't exist); the bundled `widget_test.dart` references a non-existent class and won't compile; branding is default Flutter placeholder.

---

## 4. Domain model (as it exists today)

```
User ──(created_by / updated_by)──> every entity below   (audit only)

ADMINISTRATIVE / POLITICAL GEOGRAPHY
Region 1─< District 1─< Ward 1─< Village 1─< Hamlet
                └─< Constituency        (FK → District; leaf)

Area  = polymorphic mirror of all the above
        (area_type + area_id soft reference; one row per location)

STANDALONE (no geographic link, audit only)
Parliament          PoliticalParty
```

**Shared entity conventions** (copy-pasted into all 9 entities — no shared base class):
- `Long id` (DB PK) **+** `uid` (ULID, the external id) **+** `code` (human label, prefix + zero-padded sequence).
- Audit: `createdAt`, `updatedAt`, `createdBy`, `updatedBy`.
- `isActive` soft-delete flag (all except Area and User).

**Identity model (`User`):** username, email, passwordHash, `passwordStrength`, `userType` (**ADMIN / CITIZEN / ORGANIZATION**), `status` (ACTIVE / INACTIVE / SUSPENDED / PENDING_VERIFICATION), `requirePasswordChange`, `lastLoginAt`. There is **no Role entity** — "roles" are hardwired to `UserType`. The richer citizen profile the mobile app collects (phone, DOB, national ID, address…) **has no home in the backend**.

> Notable: there is **no entity linking a user to a place** (no citizen → region/ward). For a geo-scoped reporting product, that link is foundational and must be designed in.

---

## 5. Architecture & integration (and where it breaks)

- **One backend, two intended client surfaces:** `/admin/v1/**` (built, used by the dashboard) and `/mob/v1/**` (used by the mobile app — **not built**). Both clients prepend an `/api` path.
- **Clients connect directly** with hardcoded URLs: dashboard → `http://localhost:8080/api` (in 10 service files); mobile → `http://10.0.2.2:8080/api`. A leftover `cors-proxy.js` exists for Flutter-web but isn't used. CORS on the backend is wide open (`*` + credentials).
- **Auth:** stateless JWT (HS256) with rich claims. The dashboard ignores the refresh token; the mobile app stores it but never uses it.
- **The critical break:** the **mobile app talks to a `/mob/v1` API that does not exist** (`/mob/v1/auth/login`, `/mob/v1/profiles/register`, `/refresh`, `/logout`). This is why the mobile auth layer defensively guesses at response shapes and falls back to dummy data. The mobile↔backend contract is **aspirational, not real.**

---

## 6. Why a clean rewrite is justified (condensed)

The existing code is a competent first pass but carries defects that make a fresh start cheaper than remediation:

- **Boot-blocking:** the API ships **no `application.yml`/resources at all** — no datasource, no `jwt.secret`, no port — so it does not start as delivered.
- **Security silently disabled:** `@PreAuthorize` annotations exist on 3 controllers but `@EnableMethodSecurity` was never turned on, so **any authenticated user of any type can call every admin endpoint**; a refresh token is accepted as an access token; `/actuator/**` is public; the root password is hardcoded in source *and logged in plaintext*; suspended users keep working until token expiry.
- **Structural duplication:** id/uid/code/audit logic copy-pasted across 9 entities; ~10 controllers repeat pagination/wrapper boilerplate; duplicate/dead DTOs; inconsistent naming (`*Request` vs `*RequestDto`, `q` vs `searchTerm`, `capital` vs `headquarters`), code-format and pagination-default drift.
- **Fragile mechanics:** human codes generated via `max(id)+1` (race-prone, not gap-safe); three different response shapes clients must guess between; mobile dummy-token fallbacks.

None of these are hard to fix — collectively they argue for rebuilding on clean foundations (shared base entity, DB-sequence codes, one response envelope, real RBAC + method security, externalised config + migrations) rather than patching.

---

## 7. The product the rewrite must actually build

To become "Taarifu," the rewrite needs the **civic layer that does not exist yet**. Evidence across the three apps points to:

- **Verified citizen onboarding** (mobile self-registration with national-ID-grade KYC → `Citizen`/`Profile` entity, email/OTP verification, `PENDING_VERIFICATION → ACTIVE`).
- **The core citizen action** — most likely **issue/report submission** ("report issues") and/or **engaging representatives** ("engage with leaders"), geo-scoped to the hierarchy, with categories, status workflow, attachments/location pin, and comments.
- **Notifications / feed** (the "taarifu" = *notify* sense).
- **Representatives / MPs** tying Constituency ↔ Parliament ↔ Political Party to actual people (no such entity today), and possibly **elections**.
- **Civic organisations** (the `ORGANIZATION` user type currently has no onboarding or purpose).
- **Real role-based access** spanning admin, citizen, and organisation, replacing today's binary authenticated/not.

The existing geography + political-party + parliament catalog becomes the **reference backbone** these new features hang off.

---

## 8. Open questions to resolve in the PRD

**Product / scope**
1. What is the **single core citizen action** — report issues, engage/message representatives, receive notifications, or all of these? This defines the whole missing domain.
2. Which new entities are in scope: **Report/Issue, Category, Attachment, Status workflow, Notification, Feed, Comment, Representative/MP, Election**?
3. Are **MPs / candidates / elections** in scope, and how do they relate to Constituency / Parliament / Party?
4. What is the **ORGANIZATION** user type for, and how do organisations onboard?
5. Do citizens and reports **attach to the geographic hierarchy** (Region→Hamlet / Constituency)? (Assumed yes — define the link.)

**Identity / auth**
6. Is mobile registration **public self-sign-up** or **institutional** (backend-issued credentials + verification)? Today's copy implies institutional, but no verification flow exists.
7. **RBAC model:** keep `UserType`-as-role, or introduce a proper Role/Permission model? (The module is even named `userandrole`, but no Role exists.)
8. **Refresh-token strategy:** stateless, or server-side store with rotation + revocation? (No client uses refresh today.)
9. Canonical JWT subject: keep mutable `username`, or switch to immutable `uid` (recommended)?

**Domain / data**
10. **Constituency's canonical parent** — District (current FK) or Region (current enum)? They disagree.
11. Keep the `id + uid + code` triple or simplify? Move code generation to **DB sequences**.
12. Standardise field naming and nullability across entities; decide whether the denormalized **Area** mirror survives.

**API & infra**
13. Adopt **one response envelope** for all responses (incl. pagination + health); fix the `status` (boolean) vs `statusCode` (int) confusion so client typings are correct.
14. Confirm the public contract: the `/api` prefix and the `/admin/v1` vs `/mob/v1` split.
15. **Target database:** commit to MySQL (driver present, never wired) over the in-memory H2 default; add **Flyway/Liquibase** migrations and `ddl-auto=validate`.
16. **Externalise config** for all three apps (API secrets/datasource; dashboard `API_BASE_URL`; mobile configurable base URL / build flavors).

---

### Bottom line

Taarifu is, today, a **Tanzania geographic + political reference-data catalog** with a solid admin CRUD dashboard and an **auth-only mobile prototype** — good plumbing and a clear civic-reporting *vision*, but the reporting/notification product itself (and the `/mob/v1` backend the mobile app needs) is greenfield. The rewrite should **keep the reference-data backbone, fix the foundational/security defects by design, and build the citizen civic-engagement layer that the name has always promised.**
