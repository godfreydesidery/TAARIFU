// =============================================================================
// Taarifu performance harness — shared configuration.
//
// Single source of truth for the target, API root, and environment knobs every
// k6 scenario reads. All values are overridable from the environment (k6 `-e`)
// so the same scripts run against local Docker, staging, and a region-wave env
// WITHOUT editing source (mirrors the backend's env-only config, CLAUDE.md §12).
//
// Grounding: PRD §15 NFRs (reads p95 < 500ms, writes p95 < 1s, 99.9% avail) and
// the backend's real surface — context-path /api/v1, port 8081 (application.yml).
// =============================================================================

/**
 * Reads an env var with a default. k6 exposes `-e KEY=VALUE` via __ENV.
 * @param {string} key  env var name
 * @param {string} dflt fallback when unset/empty
 * @returns {string}
 */
function env(key, dflt) {
  const v = __ENV[key];
  return v === undefined || v === '' ? dflt : v;
}

// --- Target -----------------------------------------------------------------
// BASE_URL is the host root WITHOUT the API context-path. The backend serves
// every route under /api/v1 (server.servlet.context-path in application.yml),
// so API_BASE appends it once and every scenario builds paths from API_BASE.
export const BASE_URL = env('BASE_URL', 'http://localhost:8081');
export const API_PREFIX = env('API_PREFIX', '/api/v1');
export const API_BASE = `${BASE_URL}${API_PREFIX}`;

// Actuator lives under the same context-path; used by the setup() readiness gate
// and as the cheapest possible health probe (PRD §15 observability: /health).
export const HEALTH_URL = `${API_BASE}/actuator/health`;

// --- Auth (pre-seeded tokens) ----------------------------------------------
// WHY pre-seeded bearer tokens, not a live OTP login in the hot loop:
// OtpService delivers the code OUT-OF-BAND (SMS gateway / logging stub) and
// stores only a keyed HASH — the plaintext code is NEVER in any API response
// (S-4, anti-enumeration). A black-box load script therefore cannot "read" the
// OTP. So the citizen-write and admin journeys authenticate with tokens minted
// once by the seeding step (see perf/k6/data/ + perf/README.md "Seeding").
// Reads are public (permitAll) and need no token at all.
//
// CITIZEN_BEARER must belong to a >= T2 account: filing a report is T2-gated
// (ReportController @RequiresTier("T2")), so a fresh T1 signup CANNOT file.
export const CITIZEN_BEARER = env('CITIZEN_BEARER', '');
// ADMIN_BEARER must belong to an ADMIN/ROOT account that has ALREADY completed
// the staff TOTP second factor (AdminDashboardController hasAnyRole ADMIN,ROOT;
// staff login is MFA-gated, N-4). The seeding step performs the TOTP exchange.
export const ADMIN_BEARER = env('ADMIN_BEARER', '');

// Optional path to a CSV of citizen tokens for fan-out across many VUs
// (one bearer per line). Falls back to the single CITIZEN_BEARER when absent.
export const CITIZEN_TOKENS_CSV = env('CITIZEN_TOKENS_CSV', '');

// --- Reference ids the write/track journeys need ----------------------------
// Filing a report requires a real categoryId + wardId (FileReportDto @NotNull).
// These are resolved at runtime from the public reads when not supplied, but
// can be pinned via env for a deterministic, lower-variance write test.
export const REPORT_CATEGORY_ID = env('REPORT_CATEGORY_ID', '');
export const REPORT_WARD_ID = env('REPORT_WARD_ID', '');

// --- Locale -----------------------------------------------------------------
// Swahili-first: every request carries Accept-Language: sw by default so the
// load profile matches real Tanzanian traffic and the i18n path is exercised
// (PRD §15 i18n, ADR-0010). Override with ACCEPT_LANGUAGE=en to A/B the locale.
export const ACCEPT_LANGUAGE = env('ACCEPT_LANGUAGE', 'sw');

// --- Pagination defaults (match the controllers' caps: size <= 100) ---------
export const PAGE_SIZE = parseInt(env('PAGE_SIZE', '20'), 10);

/**
 * Whether a citizen bearer is configured (write journeys are skipped without it
 * rather than hammering the API with guaranteed 401/403s that pollute results).
 * @returns {boolean}
 */
export function hasCitizenAuth() {
  return CITIZEN_BEARER.length > 0 || CITIZEN_TOKENS_CSV.length > 0;
}

/**
 * Whether an admin bearer is configured.
 * @returns {boolean}
 */
export function hasAdminAuth() {
  return ADMIN_BEARER.length > 0;
}
