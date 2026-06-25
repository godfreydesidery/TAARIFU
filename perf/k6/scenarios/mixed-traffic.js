// =============================================================================
// SCENARIO 4 — Mixed traffic (realistic blend, one run).
//
// Runs all three journeys CONCURRENTLY with a weighting that approximates real
// civic traffic: reads dominate, citizen writes are a minority, admin is a thin
// slice. This is the closest single run to "production shape" and is the one to
// use for the LAUNCH quality-gate sign-off (PERFORMANCE-PLAN "Launch gate").
//
// Weighting (default, tune via *_WEIGHT env): reads 80% / citizen 18% / admin 2%.
// Implemented with separate executors so each journey keeps its own SLO trend and
// the per-class thresholds (read p95<500ms, write p95<1s) still gate independently.
//
// Run:
//   k6 run -e PROFILE=load -e CITIZEN_BEARER=<t2> -e ADMIN_BEARER=<admin> \
//          perf/k6/scenarios/mixed-traffic.js
// =============================================================================

import { buildThresholds } from '../lib/thresholds.js';
import { stagesFor } from '../lib/profiles.js';
import { hasCitizenAuth, hasAdminAuth } from '../lib/config.js';
import { setupReportRefs } from '../lib/setup.js';

// Re-use the per-journey iteration bodies so there is ONE implementation of each
// journey (DRY — the mixed run must not drift from the focused runs).
import { publicReads } from './public-reads.js';
import { citizenJourney } from './citizen-journey.js';
import { adminDashboard } from './admin-dashboard.js';

const READ_WEIGHT = parseInt(__ENV.READ_WEIGHT || '80', 10);
const CITIZEN_WEIGHT = parseInt(__ENV.CITIZEN_WEIGHT || '18', 10);
const ADMIN_WEIGHT = parseInt(__ENV.ADMIN_WEIGHT || '2', 10);

// Scale each journey's VU stages by its weight so the blend matches the ratios.
function scaledStages(weightPct) {
  return stagesFor().map((s) => ({
    duration: s.duration,
    target: Math.max(0, Math.round((s.target * weightPct) / 100)),
  }));
}

const scenarios = {
  reads: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: scaledStages(READ_WEIGHT),
    gracefulRampDown: '20s',
    exec: 'reads',
  },
};
// Only schedule the authenticated journeys when their tokens are present, so a
// reads-only run (no seeding) still works out of the box.
if (hasCitizenAuth()) {
  scenarios.citizen = {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: scaledStages(CITIZEN_WEIGHT),
    gracefulRampDown: '20s',
    exec: 'citizen',
  };
}
if (hasAdminAuth()) {
  scenarios.admin = {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: scaledStages(ADMIN_WEIGHT),
    gracefulRampDown: '20s',
    exec: 'admin',
  };
}

export const options = {
  scenarios,
  thresholds: buildThresholds({ read: true, write: true, admin: true }),
};

/**
 * Shared setup: resolve report refs once for the whole blend. Reads/admin ignore
 * the report refs; the citizen exec uses them. Readiness is gated inside.
 */
export function setup() {
  return setupReportRefs();
}

/** Reads exec — public front door. */
export function reads(ctx) {
  // public-reads' setup output shape differs, but publicReads only needs
  // optional ids; pass an empty ctx so it self-discovers per iteration.
  publicReads({});
}

/** Citizen exec — uses the resolved category/ward refs. */
export function citizen(ctx) {
  citizenJourney(ctx);
}

/** Admin exec — back-office console blend. */
export function admin(ctx) {
  adminDashboard();
}
