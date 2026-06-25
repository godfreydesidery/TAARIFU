// =============================================================================
// Taarifu performance harness — shared setup() helpers.
//
// setup() runs ONCE per k6 run, before the load, on a single VU. We use it to:
//   1. Gate on backend readiness (don't load-test a cold/booting context — the
//      numbers would be meaningless), and
//   2. Resolve REAL reference ids from the public reads so the per-VU loop hits
//      real rows (a guessed UUID 404s and skews the SLO; a real id exercises the
//      true query plan). This is the honest way to measure under PRD §15.
// =============================================================================

import http from 'k6/http';
import { fail, sleep } from 'k6';
import {
  API_BASE,
  HEALTH_URL,
  REPORT_CATEGORY_ID,
  REPORT_WARD_ID,
} from './config.js';
import { headers } from './http.js';

/**
 * Blocks until the backend reports UP, or fails the run fast. Polls the actuator
 * health endpoint (PRD §15 observability) a few times to absorb a slow start.
 * @throws via k6 fail() when the backend never becomes ready.
 */
export function awaitReady() {
  for (let i = 0; i < 10; i++) {
    const res = http.get(HEALTH_URL, { headers: headers() });
    if (res.status === 200) return;
    // 3s * 10 = up to 30s grace for a cold JVM/Flyway boot. sleep() is allowed
    // inside setup() and does not affect per-iteration latency metrics.
    sleep(3);
  }
  fail(
    `Backend not ready at ${HEALTH_URL}. Start the stack (docker compose up -d) ` +
      `and confirm GET /api/v1/actuator/health returns 200 before running k6.`
  );
}

/**
 * Resolves a representative id, a petition id, and a region id from the public
 * lists for the read scenario to drill into. All best-effort — a null id simply
 * means that detail sub-call is skipped (the list reads still run).
 * @returns {{repId:?string, petitionId:?string, regionId:?string}}
 */
export function setupReferenceData() {
  awaitReady();
  return {
    repId: firstPublicId(`${API_BASE}/representatives?page=0&size=1`),
    petitionId: firstPublicId(`${API_BASE}/petitions?page=0&size=1`),
    regionId: firstPublicId(`${API_BASE}/regions?page=0&size=1`),
  };
}

/**
 * Resolves the category + ward a report must reference (FileReportDto requires
 * both, @NotNull). Prefers pinned env ids for determinism; otherwise discovers
 * them from the public reads. Returns nulls if discovery fails so the scenario
 * can skip the write gracefully (and report WHY) rather than 400-spamming.
 * @returns {{categoryId:?string, wardId:?string}}
 */
export function setupReportRefs() {
  awaitReady();
  const categoryId =
    REPORT_CATEGORY_ID || firstPublicId(`${API_BASE}/issue-categories?page=0&size=1`);
  // Wards: resolved via a region -> district -> wards walk (no top-level /wards
  // list of all wards is exposed publicly; the chain mirrors how a client does it).
  const wardId = REPORT_WARD_ID || discoverWardId();
  return { categoryId, wardId };
}

/** Walks region -> district -> ward to find a usable ward public id. */
function discoverWardId() {
  const regionId = firstPublicId(`${API_BASE}/regions?page=0&size=1`);
  if (!regionId) return null;
  const districtId = firstPublicId(
    `${API_BASE}/regions/${regionId}/districts?page=0&size=1`
  );
  if (!districtId) return null;
  return firstPublicId(`${API_BASE}/districts/${districtId}/wards?page=0&size=1`);
}

/**
 * GETs a paged endpoint and returns the first item's public id, or null.
 * @param {string} url
 * @returns {?string}
 */
function firstPublicId(url) {
  const res = http.get(url, { headers: headers() });
  if (res.status !== 200) return null;
  try {
    const d = res.json('data');
    if (Array.isArray(d) && d.length > 0) {
      return d[0].id || d[0].publicId || null;
    }
  } catch (e) {
    /* fall through */
  }
  return null;
}
