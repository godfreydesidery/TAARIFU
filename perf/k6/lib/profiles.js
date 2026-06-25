// =============================================================================
// Taarifu performance harness — load profiles (ramping VU stages).
//
// One PROFILE env var selects the shape of the run; every scenario reuses these
// so smoke/load/stress/soak mean the same thing across all journeys. This keeps
// the test matrix honest: the same journey at four intensities (PERFORMANCE-PLAN
// "scenarios x profiles").
//
//   smoke  — 1-2 VUs, ~1 min: correctness + SLO sanity on a tiny load. The CI
//            gate every PR can afford; proves the script + endpoints are wired.
//   load   — ramp to expected pilot concurrency, hold, ramp down. The PRD §15
//            "under expected load" SLO check. This is the launch-gate profile.
//   stress — ramp BEYOND expected load until SLOs bend, to find the knee/limit
//            and confirm graceful degradation (PRD §15 resilience), not collapse.
//   soak   — moderate load held for a long duration to surface leaks, pool
//            exhaustion, GC creep, and outbox/queue backlog over time.
//
// All sizes are env-overridable so a laptop run and a staging run differ only by
// flags, never by code (e.g. -e LOAD_TARGET_VUS=200).
// =============================================================================

/** @param {string} k @param {string|number} d @returns {string} */
function env(k, d) {
  const v = __ENV[k];
  return v === undefined || v === '' ? String(d) : v;
}
/** @param {string} k @param {number} d @returns {number} */
function envInt(k, d) {
  return parseInt(env(k, d), 10);
}

export const PROFILE = env('PROFILE', 'smoke');

// Tunable targets per profile (sensible laptop-friendly defaults; raise on a
// real staging box). "Expected pilot load" is a placeholder the team MUST
// calibrate from real onboarding numbers — see PERFORMANCE-PLAN "Open inputs".
const SMOKE_VUS = envInt('SMOKE_VUS', 2);
const LOAD_TARGET_VUS = envInt('LOAD_TARGET_VUS', 50);
const STRESS_TARGET_VUS = envInt('STRESS_TARGET_VUS', 300);
const SOAK_VUS = envInt('SOAK_VUS', 40);
const SOAK_DURATION = env('SOAK_DURATION', '30m');

/**
 * Ramping-VU executor stages for the selected PROFILE. Returned shape plugs
 * straight into a k6 scenario's `stages`.
 * @returns {Array<{duration:string,target:number}>}
 */
export function stagesFor() {
  switch (PROFILE) {
    case 'smoke':
      return [
        { duration: '15s', target: SMOKE_VUS },
        { duration: '45s', target: SMOKE_VUS },
        { duration: '10s', target: 0 },
      ];
    case 'load':
      // Gentle ramp -> hold at expected concurrency -> ramp down (steady state).
      return [
        { duration: '1m', target: LOAD_TARGET_VUS },
        { duration: '5m', target: LOAD_TARGET_VUS },
        { duration: '1m', target: 0 },
      ];
    case 'stress':
      // Step beyond expected load to find the knee; watch for graceful degrade.
      return [
        { duration: '2m', target: LOAD_TARGET_VUS },
        { duration: '3m', target: STRESS_TARGET_VUS },
        { duration: '3m', target: STRESS_TARGET_VUS },
        { duration: '2m', target: 0 },
      ];
    case 'soak':
      // Long steady hold to surface leaks/backlog.
      return [
        { duration: '2m', target: SOAK_VUS },
        { duration: SOAK_DURATION, target: SOAK_VUS },
        { duration: '2m', target: 0 },
      ];
    default:
      throw new Error(
        `Unknown PROFILE='${PROFILE}'. Use one of: smoke | load | stress | soak`
      );
  }
}

/**
 * A complete ramping-vus scenario block for the current PROFILE.
 * @param {string} exec name of the exported scenario function to run.
 * @param {object} [extra] optional executor overrides (e.g. startVUs, tags).
 * @returns {object} a k6 options.scenarios entry value.
 */
export function rampingScenario(exec, extra = {}) {
  return Object.assign(
    {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: stagesFor(),
      gracefulRampDown: '20s',
      exec,
    },
    extra
  );
}
