// =============================================================================
// SCENARIO 3 — Admin dashboard load (back-office console).
//
// Journey: a signed-in ADMIN/ROOT loads the Angular console's home: the
// aggregated dashboard stats, plus the heavier analytics tiles and the admin
// reports/users lists the operator pages through (PRD §10 M14, UC-H06).
//
// These are READS but the dashboard aggregates across every module's stats
// provider (ADR-0013) and the analytics endpoints run grouped queries — so this
// scenario is the canary for the most expensive read path. SLO class: ADMIN
// (held to the read budget p95 < 500ms by default; relax via ADMIN_P95_MS only
// with a documented justification — PRD §15 gives no separate admin budget).
//
// -------------------------------------------------------------------------
// Auth: AdminDashboardController is hasAnyRole('ADMIN','ROOT'), and staff login
// is MFA-gated (TOTP, N-4). A black-box script cannot complete TOTP in-loop, so
// ADMIN_BEARER MUST be a PRE-SEEDED token from an admin that already finished the
// TOTP exchange (perf/README.md "Seeding"). Concurrency here is modest by design
// — there are few operators, not millions — so the default profile targets are
// smaller (override with LOAD_TARGET_VUS if your console has many concurrent ops).
// -------------------------------------------------------------------------
//
// Run:
//   k6 run -e PROFILE=smoke -e ADMIN_BEARER=<admin-token> perf/k6/scenarios/admin-dashboard.js
//   k6 run -e PROFILE=load  -e LOAD_TARGET_VUS=20 -e ADMIN_BEARER=<admin-token> ...
// =============================================================================

import { sleep, group, fail } from 'k6';
import { API_BASE, ADMIN_BEARER, PAGE_SIZE, hasAdminAuth } from '../lib/config.js';
import { request } from '../lib/http.js';
import { buildThresholds } from '../lib/thresholds.js';
import { rampingScenario } from '../lib/profiles.js';
import { awaitReady } from '../lib/setup.js';

// NOTE: admin concurrency is naturally low (a handful of operators, not millions).
// The shared profiles still apply, but for `load`/`stress` you will usually pass a
// smaller -e LOAD_TARGET_VUS / -e STRESS_TARGET_VUS than the citizen scenarios use.

export const options = {
  scenarios: { admin_dashboard: rampingScenario('adminDashboard') },
  thresholds: buildThresholds({ admin: true }),
};

/**
 * setup(): readiness gate + require an admin token. Fails fast with guidance if
 * absent (rather than producing a run that is 100% 401/403 and looks "fast").
 */
export function setup() {
  awaitReady();
  if (!hasAdminAuth()) {
    fail(
      'Admin scenario requires ADMIN_BEARER=<token> for an ADMIN/ROOT account ' +
        'that has completed staff TOTP. See perf/README.md "Seeding (admin)".'
    );
  }
}

/** Helper: an ADMIN-class GET with the admin bearer + admin SLO trend. */
function adminGet(url, name) {
  return request('GET', url, {
    slo: 'admin',
    name,
    bearer: ADMIN_BEARER,
    expect: [200],
  });
}

/** One operator "load the console + page around" iteration. */
export function adminDashboard() {
  group('dashboard-home', () => {
    // The cross-module aggregate the console renders first (the canary).
    adminGet(`${API_BASE}/admin/dashboard/stats`, 'GET /admin/dashboard/stats');
  });

  group('analytics-tiles', () => {
    // Heavier grouped-query tiles the analytics dashboard shows (UC-H06).
    adminGet(`${API_BASE}/admin/analytics/reports/volume`, 'GET /analytics/reports/volume');
    adminGet(`${API_BASE}/admin/analytics/reports/ttfr`, 'GET /analytics/reports/ttfr');
    adminGet(`${API_BASE}/admin/analytics/reports/sla-breaches`, 'GET /analytics/sla-breaches');
    adminGet(`${API_BASE}/admin/analytics/verification/funnel`, 'GET /analytics/verification/funnel');
  });

  group('operator-lists', () => {
    // The lists an operator pages through working the queues.
    adminGet(`${API_BASE}/admin/reports?page=0&size=${PAGE_SIZE}`, 'GET /admin/reports');
    adminGet(`${API_BASE}/admin/stats`, 'GET /admin/stats');
    adminGet(`${API_BASE}/admin/users?page=0&size=${PAGE_SIZE}`, 'GET /admin/users');
  });

  sleep(Math.random() * 2 + 1); // operator think-time between screens
}
