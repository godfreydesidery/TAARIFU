# Taarifu — Admin Console (`web-admin`)

Angular 18 admin console for the Taarifu Tanzania civic-engagement platform. Standalone components,
TypeScript strict, RxJS, lazy-loaded routes, **Bootstrap 5.3** UI, **Swahili-first** i18n (SW default +
EN fallback via `@ngx-translate`), and a typed client over the Spring Boot API's single response
envelope.

This is a **buildable foundation / vertical slice** that establishes the patterns (auth, envelope
handling, i18n, layout, list + CRUD) for the rest of the console — not the full product.

---

## Prerequisites

- **Node 22** and **npm 10** (verified versions).
- The Taarifu **backend** running locally on **`http://localhost:8081`** (context-path `/api/v1`).
  Auth + the feature endpoints (`/auth/login/password`, `/regions`, `/representatives`, `/parties`,
  `/issue-categories`) must be reachable.

> The Angular CLI is **not global**. Use `npm run …` scripts or `npx ng …`.

---

## Run it

```bash
npm install
npm start          # ng serve → http://localhost:4200
```

`npm start` serves on `:4200`. By default the app calls the backend **directly** at
`http://localhost:8081/api/v1` (see `src/environments/environment.development.ts`). The backend must
allow the `http://localhost:4200` origin (CORS), or use the bundled dev proxy instead:

- A **dev proxy** is wired in `angular.json` (`proxy.conf.json`) forwarding `/api/v1/*` → `:8081`.
  To route through it (avoids browser CORS), change `apiUrl` in `environment.development.ts` to the
  relative `'/api/v1'`. With that, all API calls go same-origin through the dev server.

### Build (production)

```bash
npm run build      # → dist/web-admin ; honours bundle budgets
```

### Test

```bash
npm test           # Karma + ChromeHeadless, single run (no watch)
```

---

## Configuration — the ONLY place the API URL lives

`src/environments/environment.ts` (prod) and `environment.development.ts` (dev) hold `apiUrl`.
**No component, service, or interceptor hardcodes a URL** (CLAUDE.md §8). To point at another host,
edit the environment file (or supply a production `fileReplacement`).

```ts
apiUrl: 'http://localhost:8081/api/v1'
```

---

## Architecture (how it maps to the backend contract)

### Response envelope (`ARCHITECTURE.md §5`)

Every backend response — success and error — is `{ success, statusCode, message, data, meta, timestamp }`.

- `src/app/core/api/api-response.model.ts` — typed `ApiResponse<T>`, `PageMeta`, `ApiError`, `Page<T>`.
- `src/app/core/api/api-client.service.ts` — **the** HTTP gateway; unwraps `data` and bundles paged
  `content + meta`. Feature services never touch the envelope.
- `src/app/core/interceptors/api-response.interceptor.ts` — normalises **every** error into a typed
  `ApiError` (reading the machine code at `data.code`, field errors at `data.errors[]`) and raises a
  localised toast. `401` (refresh) and validation errors (shown inline) are not toasted.

### Auth (`ARCHITECTURE.md §6`)

- Login: `POST /auth/login/password` → the envelope's `data` carries the token pair (+ MFA flag for
  staff). Tokens stored by `token-storage.service.ts`.
- `auth.interceptor.ts` attaches `Authorization: Bearer` + `Accept-Language`, and on a `401` calls
  `POST /auth/refresh` **once**, replays the request, or clears the session.
- `auth.guard.ts` protects the shell (client convenience — the **server** is the real gate; tier/roles
  decoded from the JWT are UI hints only).

### i18n (Swahili-first)

- `@ngx-translate` with lazy JSON dictionaries in `public/i18n/{sw,en}.json`. Swahili is the default;
  English is the fallback (SW → EN → key). The active locale also sets `Accept-Language` so the backend
  localises envelope messages. Switch language from the header.

### Layout

- `layout/shell` — header + collapsible (off-canvas on mobile) sidenav + routed content. Icon+label
  nav for low-literacy accessibility; WCAG 2.1 AA markup (skip-link, `aria-*`, semantic tables).

### Feature modules (consume REAL endpoints)

| Feature | Endpoints | Pattern shown |
|---|---|---|
| **Geography** | `GET /regions`, `GET /regions/{id}/districts` | Server-paginated list + lazy drill-down |
| **Representatives** | `GET /representatives` | Debounced search + type/status filters + pagination |
| **Parties** | `GET /parties` | Debounced search + pagination |
| **Issue Categories** | `GET /issue-categories/admin`, `POST`/`PUT`/`DELETE /issue-categories` | Full Admin CRUD + typed reactive form + server-field-error mapping |

All four reuse the shared `shared/components/pagination.component.ts` and the loading / empty / error
state pattern.

---

## Project layout

```
src/
├── environments/                 environment.ts / environment.development.ts (apiUrl)
├── app/
│   ├── core/
│   │   ├── api/                   ApiResponse model, ApiError, ApiClient
│   │   ├── auth/                  models, token storage, JWT util, AuthService, guard
│   │   ├── i18n/                  LocaleService, translate loader
│   │   ├── interceptors/         authInterceptor, apiResponseInterceptor
│   │   └── notifications/        ToastService + container
│   ├── shared/components/        PaginationComponent
│   ├── layout/shell/             ShellComponent (header + sidenav + content)
│   ├── features/
│   │   ├── auth/                 LoginComponent
│   │   ├── dashboard/            DashboardComponent
│   │   ├── geography/            models, service, regions list (+ districts), routes
│   │   ├── institutions/         models, service, representatives + parties lists
│   │   └── categories/           models, service, list + form (CRUD), routes
│   ├── app.config.ts             providers: router, http+interceptors, translate, locale init
│   └── app.routes.ts             login + guarded shell with lazy feature children
└── public/i18n/                  sw.json (default), en.json
```

---

## Notes / known follow-ups

- **OpenAPI client generation** (recommended): the backend serves an OpenAPI spec at
  `/api/v1/openapi.json`. The hand-written DTO interfaces here should later be **generated** from that
  spec (e.g. `openapi-generator` / `orval`) so the client never drifts from the contract. The current
  models match the backend DTOs as of this slice.
- The Bootstrap CSS optimizer prints a benign `.form-floating>~label` selector warning on build — it is
  from Bootstrap's own CSS, not app code, and does not fail the build.
- MFA (staff TOTP second factor at `/auth/login/totp`) is detected and surfaced but not yet implemented
  in this foundation slice.
