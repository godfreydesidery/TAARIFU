// =============================================================================
// Taarifu performance harness — SLO thresholds (PRD §15).
//
// These translate the PRD §15 Non-Functional Requirements into k6 pass/fail
// gates. A scenario that breaches a threshold FAILS the run (k6 exits non-zero),
// so CI / the launch quality gate can block on real numbers, not vibes.
//
//   PRD §15: "API p95 < 500ms for reads, < 1s for writes under expected load"
//   PRD §15: "99.9% monthly for core APIs" -> per-run we assert error rate < 0.1%
//
// WHY split read vs write metrics: a single global http_req_duration would mix
// a 1ms cached region read with a multi-write report-file and hide the truth.
// Each scenario tags its requests trend=read|write|admin (see http.js) and we
// gate each tag separately against its own budget.
// =============================================================================

import { Trend, Rate, Counter } from 'k6/metrics';

// --- Custom metrics, tagged per operation class -----------------------------
// Latency trends, one per SLO class. Scenarios record into these via http.js.
export const readLatency = new Trend('taarifu_read_latency', true);
export const writeLatency = new Trend('taarifu_write_latency', true);
export const adminLatency = new Trend('taarifu_admin_latency', true);

// Functional-correctness rate: did the response match its expected check?
// (HTTP 2xx AND the ApiResponse envelope success flag where applicable.)
export const journeyOk = new Rate('taarifu_journey_ok');

// Hard failures (5xx / transport errors) -> availability proxy for the 99.9% SLO.
export const serverErrors = new Counter('taarifu_server_errors');

// --- SLO budgets (milliseconds) ---------------------------------------------
// Centralised so the plan doc and the scripts cannot drift. Override per env
// (e.g. a thinner staging box) with READ_P95_MS / WRITE_P95_MS if justified —
// but the DEFAULTS are the PRD §15 contract and should be what CI gates on.
export const READ_P95_MS = parseInt(__ENV.READ_P95_MS || '500', 10); // PRD §15 reads
export const WRITE_P95_MS = parseInt(__ENV.WRITE_P95_MS || '1000', 10); // PRD §15 writes
// Admin dashboard aggregates across modules; it is a read but a heavier one.
// PRD §15 gives no separate admin budget, so we hold it to the read SLO by
// default (the strict, defensible choice) and allow a documented relaxation.
export const ADMIN_P95_MS = parseInt(__ENV.ADMIN_P95_MS || '500', 10);

// Max tolerated failed-request fraction. 99.9% availability -> 0.1% errors.
// Smoke/load hold the strict 0.1%; stress/soak may relax via ERROR_RATE_MAX.
export const ERROR_RATE_MAX = parseFloat(__ENV.ERROR_RATE_MAX || '0.001');

/**
 * Builds the k6 `thresholds` block. Each scenario picks the classes it touches.
 *
 * Threshold expressions are abortOnFail:false by default so a run still produces
 * a full summary even when a budget is missed; CI reads the non-zero exit code.
 *
 * @param {{read?:boolean, write?:boolean, admin?:boolean}} classes which SLO
 *        classes this scenario exercises.
 * @returns {object} a k6 options.thresholds map.
 */
export function buildThresholds(classes) {
  const t = {
    // Global guard rails that apply to every scenario.
    http_req_failed: [`rate<${ERROR_RATE_MAX}`], // availability proxy (PRD §15)
    taarifu_journey_ok: ['rate>0.99'], // functional correctness of the journey
    checks: ['rate>0.99'], // k6 check() pass rate
  };
  if (classes.read) {
    t['taarifu_read_latency'] = [`p(95)<${READ_P95_MS}`, `p(99)<${READ_P95_MS * 2}`];
  }
  if (classes.write) {
    t['taarifu_write_latency'] = [`p(95)<${WRITE_P95_MS}`, `p(99)<${WRITE_P95_MS * 2}`];
  }
  if (classes.admin) {
    t['taarifu_admin_latency'] = [`p(95)<${ADMIN_P95_MS}`];
  }
  return t;
}
