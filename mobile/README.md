# Taarifu — Citizen App (Flutter)

The Swahili-first, offline-first, low-data citizen app for **Taarifu**, Tanzania's
civic-engagement platform. This is the **foundation slice**: tiered-identity
onboarding (phone + OTP → T1), the single response-envelope API client, secure
token storage, i18n (SW default / EN), an offline-first read-cache seam, and two
citizen screens against the real backend (personalised **feed** and
**find-my-representative**).

Grounded in `../PRD.md`, `../CLAUDE.md`, and `../docs/architecture/ARCHITECTURE.md`.

## Tech

- Flutter / Dart 3.11, **flutter_bloc** state, package-by-feature `lib/` layout.
- **dio** networking tuned for 2G/3G (timeouts, gzip, bounded retry-with-backoff,
  `Idempotency-Key` support), **flutter_secure_storage** (Keystore) for tokens,
  **connectivity_plus** as a fast offline pre-check, **gen-l10n** for i18n.
- All user-facing strings externalised to `lib/l10n/app_sw.arb` (template) and
  `lib/l10n/app_en.arb`. Generated localizations are **git-ignored** and produced
  by `flutter gen-l10n` (runs automatically on `flutter pub get`).

## Configurable base URL (never hardcoded)

The backend base URL — including the `/api/v1` context path — is a build-time
input via `--dart-define`, **not** a source literal (the legacy app hardcoded it).

| Env key | Default | Meaning |
|---|---|---|
| `TAARIFU_API_BASE_URL` | `http://10.0.2.2:8081/api/v1` | Android emulator → host `localhost:8081`. |

Examples:

```bash
# Android emulator against a locally running backend (uses the default):
flutter run

# A physical device on the same LAN (replace with your host IP):
flutter run --dart-define=TAARIFU_API_BASE_URL=http://192.168.1.50:8081/api/v1

# Staging:
flutter run --dart-define=TAARIFU_API_BASE_URL=https://staging.taarifu.example/api/v1
```

> `10.0.2.2` is the Android emulator's alias for the host machine. A physical
> device cannot reach it — use the host's LAN IP (and ensure the backend binds
> `0.0.0.0`).

## Run / verify

```bash
flutter pub get        # also runs gen-l10n (generate: true)
flutter analyze        # must be clean (0 issues)
flutter test           # envelope decode + auth-bloc state machine
flutter run            # boots onboarding → T1 → feed + find-my-rep
```

> **Toolchain note (this environment):** `flutter` needs Windows Git **and**
> `C:\Windows\System32` on PATH (the launcher's `where.exe` lives there). Run via
> a shell where both are present, e.g. PowerShell:
> `$env:Path = "C:\Program Files\Git\cmd;C:\Windows\System32;" + $env:Path`.

## Backend endpoints consumed (real contract)

| Flow | Method + path |
|---|---|
| Request signup OTP | `POST /auth/otp/request` → `202 { challengeId }` |
| Complete signup (→ T1) | `POST /auth/signup` → `201 { userPublicId, tier, tokens }` |
| Login (OTP) | `POST /auth/login/otp/request`, `POST /auth/login/otp` |
| Login (password) | `POST /auth/login/password` |
| Refresh | `POST /auth/refresh` |
| Logout | `POST /auth/logout` |
| Regions (Mikoa) | `GET /regions` (public) |
| GPS → ward | `GET /locations/resolve?lat=&lng=` (public) |
| Find my reps | `GET /representatives/by-ward/{wardId}` (public) |
| Personalised feed | `GET /feed` (auth, T1) |

All responses use the single envelope
`{ success, statusCode, message, data, meta, timestamp }`; the stable machine
error code is at `data.code` and field errors at `data.errors[]` — see
`lib/core/network/api_response.dart`.

## Layout (package-by-feature)

```
lib/
├── main.dart                      # entry: AppConfig.fromEnvironment() → DI → app
├── app.dart                       # MaterialApp, i18n, theme, AuthStatus routing
├── l10n/                          # app_sw.arb (template), app_en.arb
├── core/
│   ├── config/app_config.dart     # --dart-define base URL
│   ├── di/app_dependencies.dart   # composition root
│   ├── network/                   # ApiResponse, ApiClient, exceptions, failure copy
│   ├── storage/                   # TokenStore (secure), JsonCache (offline-first seam)
│   ├── theme/  widgets/           # theme + reusable load/empty/error views
└── features/
    ├── auth/     {data, bloc, view}   # tiered onboarding
    ├── feed/     {data, bloc, view}   # personalised feed
    ├── representatives/{data,bloc,view}  # find-my-rep
    ├── geography/{data}               # regions + GPS resolve
    └── shell/                         # signed-in bottom-nav shell
```

## Offline-first & low-data posture

- **Reads**: `JsonCache` read-through — feed, regions, and rep bundles render
  from the last-known payload when the network drops. (In-memory in this slice;
  the production swap to Drift/Isar is drop-in behind the same interface.)
- **Writes** (later slices): the `Idempotency-Key` plumbing is already in
  `ApiClient.post` so an offline-queued submit replays without duplication.
- **Data-frugal**: gzip, lean snippet-only feed items, list virtualization,
  `const` widgets, no autoplay/heavy media. Small dependency set keeps the APK
  download cheap for citizens on tiny bundles.

## Not yet built (intentional, foundation scope)

Report draft + outbox sync, FCM push + notification preferences, profile (→T2)
and NIDA/voter (→T3) steps, biometric app-lock, cert pinning, and the persistent
on-disk cache. The seams (cache interface, idempotency header, tiered auth model)
are in place so these land without reshaping the foundation.
