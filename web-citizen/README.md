# Taarifu — Citizen Web PWA (`web-citizen`)

The **citizen-facing**, **Swahili-first**, **installable PWA** for Taarifu — Tanzania's civic-engagement
platform. Built with **Angular 18** (standalone components, signals, new control-flow), **ngx-translate**
(SW default + EN), **Bootstrap 5.3**, and the **@angular/service-worker** for offline-first behaviour.

> This is a **separate application** from `web-admin`. The admin console is English-first and
> desktop-oriented; this app is **Swahili-first, mobile-first, low-data, and offline-first** for the
> primary persona **P1 Amina** — a Dar es Salaam citizen on a low-end Android over a slow link
> (PRD §14/§15).

---

## What it does (vertical slice)

| Area | Screen | Route | Auth |
| --- | --- | --- | --- |
| **Scaffold + PWA** | Installable app shell, SW offline cache, SW/EN i18n, bottom nav | — | — |
| **Auth** | Phone + OTP (tiered-identity aware), two-step | `/auth` | public |
| **Feed** | Public reports + petitions, elegant cards, skeletons/empty/error | `/feed` | public |
| **Search** | Cross-entity discovery (debounced) | `/feed/search` | public |
| **Find my rep** | Cascading Mkoa→Wilaya→Kata picker → MP (Mbunge) + Councillor (Diwani) | `/representatives` | public |
| **File a report** | Category + area picker, opt-in GPS, anonymity (sensitive), **offline draft** | `/report` | citizen |
| **Track my reports** | List + offline drafts; status timeline | `/track`, `/track/:id` | citizen |

Public screens are **guest-readable and offline-cached**; identity actions are guarded client-side
(`authGuard`) — the server's `@PreAuthorize`/`@RequiresTier` is the real gate.

---

## Architecture (copied from the `web-admin` pattern)

- **`environment.apiUrl` is RELATIVE** (`/api/v1`). In dev the bundled **`proxy.conf.json`** forwards
  `/api/v1/*` → `http://localhost:8081` (the Spring Boot server with
  `server.servlet.context-path=/api/v1`, port `8081`), avoiding CORS. In prod the app is served
  same-origin behind a reverse proxy.
- **Response envelope** — `ApiResponse<T>` (`{ success, statusCode, message, data, meta }`) is unwrapped
  by `ApiClient`; errors are normalised to a typed `ApiError` (UI branches on the stable machine `code`,
  never the localised message).
- **Functional interceptors** —
  - `authInterceptor`: attaches the bearer token + `Accept-Language` (active locale, SW-first); refreshes
    once on a 401.
  - `apiResponseInterceptor`: maps every failure to `ApiError` and toasts the server's localised message
    (suppressing 401 + inline validation).
- **i18n** — all strings externalised to `public/i18n/{sw,en}.json`; **SW is the default**, EN the
  fallback (SW → EN → key chain). Loaded lazily via a bare `HttpClient` (bypasses interceptors to avoid a
  DI cycle).
- **Offline-first reporting** — `DraftQueueService` (durable `localStorage` queue with idempotency keys,
  FIFO order, bounded retention) + `ReportService` (ordered idempotent sync on reconnect). Policy:
  **server-authoritative for status, client-preserved for unsent drafts**.
- **PWA** — `manifest.webmanifest`, `@angular/pwa` service worker (`ngsw-config.json`): prefetched app
  shell + a **read-cache** (freshness strategy) for the public read APIs and "my" reads; `PwaUpdateService`
  prompts on a new version.

---

## Run it

```bash
cd web-citizen
npm install

# Dev server with the backend proxy (expects the API on :8081)
npm start            # http://localhost:4200  (proxies /api/v1 → :8081)

# Production build (includes the service worker)
npm run build        # → dist/web-citizen
```

The service worker is **disabled in dev** (so live-reload works) and **enabled in production**. To test
the PWA/offline behaviour, build for production and serve `dist/web-citizen` over HTTP(S):

```bash
npm run build
npx http-server dist/web-citizen -p 4300   # then install + go offline in DevTools
```

---

## API endpoints consumed

All under the `/api/v1` base. Public (`permitAll`) unless marked **auth**.

| Flow | Method + path |
| --- | --- |
| Request signup OTP | `POST /auth/otp/request` |
| Complete signup (→ T1 + tokens) | `POST /auth/signup` |
| Request login OTP | `POST /auth/login/otp/request` |
| Complete login | `POST /auth/login/otp` |
| Refresh token | `POST /auth/refresh` |
| Logout | `POST /auth/logout` *(auth)* |
| Feed — public reports | `GET /public/reports` |
| Feed — petitions | `GET /petitions` |
| Announcement detail | `GET /announcements/{id}` |
| Discovery search | `GET /search?q=` |
| Issue categories (picker) | `GET /issue-categories` |
| Regions / districts / wards | `GET /regions`, `GET /regions/{id}/districts`, `GET /districts/{id}/wards`, `GET /wards?q=` |
| File a report | `POST /reports` *(auth)* |
| My reports | `GET /reports` *(auth)* |
| My report detail | `GET /reports/{id}` *(auth)* |
| My report timeline | `GET /reports/{id}/timeline` *(auth)* |
| Find my rep (by ward) | `GET /representatives/by-ward/{wardId}` |
| Representative directory | `GET /representatives?q=&type=` |

---

## What's stubbed / deferred (next slices)

- **Idempotency-Key header** — the draft queue generates and tracks an idempotency key per draft and
  de-dups client-side (one key → one confirmed report removes the draft). Sending the key as an
  `Idempotency-Key` HTTP header requires a small interceptor/gateway addition; until then ordering +
  client-side de-dup are guaranteed, but a true server-side replay guard is pending a backend contract.
- **Unified auth-request endpoint** — the flow currently requests the **signup** OTP and lets the backend
  create-or-activate a T1 account on verify; if the phone already exists it transparently retries as a
  login verify. A single "request code" endpoint (signup-or-login) would remove the retry branch.
- **Attachments** — `attachmentRefs` is modelled but the upload UI (media module, size/type limits,
  thumbnails) is deferred.
- **Report public detail page, petition signing, announcement feed list, profile/tier-upgrade (NIDA),
  notifications, token wallet** — out of scope for this slice.
- **PWA icons** — placeholder solid-green PNGs; replace with the brand mark before launch.
- **Tests** — Jasmine/Karma is configured (`npm test`); component + offline-sync specs are a follow-up.

---

## Accessibility & data-cost notes

- WCAG 2.1 AA: semantic landmarks, skip link, focus-visible rings, `role="alert"/"status"` for toasts +
  banners, colour never the sole status signal (status pills carry a translated label), 44px+ targets,
  numeric `inputmode` for OTP, `prefers-reduced-motion` respected.
- Low-literacy: icon **+** label nav, plain simple Swahili, bottom-nav one-handed layout.
- Data cost: lazy routes, debounced search, aggressive pagination, skeletons (no spinners-of-doom),
  lazy per-locale i18n, SW read-cache, bundle budgets in `angular.json`.
