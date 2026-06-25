// =============================================================================
// SCENARIO 1 — Public reads (the platform's front door).
//
// Journey: an unauthenticated Guest (incl. a feature-phone web user) browses the
// public reference surface — regions, the representative directory + a profile,
// public petitions + a profile, issue categories. These are permitAll() reads
// (GeographyController / RepresentativeController / PetitionController) and the
// hottest, most cacheable paths on the system (PRD §22.6 "front door").
//
// SLO class: READ -> p95 < 500ms (PRD §15). This is the journey most likely to
// dominate national-scale traffic, so it carries the strict read budget.
//
// Run:
//   k6 run -e PROFILE=smoke perf/k6/scenarios/public-reads.js
//   k6 run -e PROFILE=load  -e BASE_URL=https://staging.taarifu.go.tz ...
// =============================================================================

import { sleep, group } from 'k6';
import { API_BASE, PAGE_SIZE } from '../lib/config.js';
import { get, data } from '../lib/http.js';
import { buildThresholds } from '../lib/thresholds.js';
import { rampingScenario } from '../lib/profiles.js';
import { setupReferenceData } from '../lib/setup.js';

export const options = {
  scenarios: { public_reads: rampingScenario('publicReads') },
  thresholds: buildThresholds({ read: true }),
  // Lower noise: don't fail the whole run on a single check, gate on the rates.
  noConnectionReuse: false,
};

/**
 * setup() runs ONCE before the load. It gates on backend readiness and resolves
 * a few real ids (a representative, a petition) so the per-VU loop fetches REAL
 * profiles rather than guessing UUIDs (which would 404 and skew the read SLO).
 * @returns {{repId:?string, petitionId:?string, regionId:?string}}
 */
export function setup() {
  return setupReferenceData();
}

/**
 * One pass of the public-read journey. Weighted toward list views (what most
 * Guests hit) with a couple of detail fetches.
 * @param {{repId:?string, petitionId:?string, regionId:?string}} ctx setup output.
 */
export function publicReads(ctx) {
  group('geography', () => {
    // List regions (Mikoa) — the canonical cold-start reference read.
    const regions = get(`${API_BASE}/regions?page=0&size=${PAGE_SIZE}`, {
      name: 'GET /regions',
    });
    // Drill into a region's districts (Wilaya) when we have one.
    const regionId = ctx.regionId || firstId(regions);
    if (regionId) {
      get(`${API_BASE}/regions/${regionId}/districts?page=0&size=${PAGE_SIZE}`, {
        name: 'GET /regions/{id}/districts',
      });
    }
  });

  group('representatives', () => {
    // The directory list (find-my-rep front door, UC-C01/C06).
    get(`${API_BASE}/representatives?page=0&size=${PAGE_SIZE}`, {
      name: 'GET /representatives',
    });
    // A representative profile (UC-C02) — heavier read with joins.
    if (ctx.repId) {
      get(`${API_BASE}/representatives/${ctx.repId}`, {
        name: 'GET /representatives/{id}',
      });
    }
  });

  group('petitions', () => {
    // Public petitions list (engagement front door).
    const petitions = get(`${API_BASE}/petitions?page=0&size=${PAGE_SIZE}`, {
      name: 'GET /petitions',
    });
    const petitionId = ctx.petitionId || firstId(petitions);
    if (petitionId) {
      get(`${API_BASE}/petitions/${petitionId}`, {
        name: 'GET /petitions/{id}',
      });
    }
  });

  group('categories', () => {
    // Issue categories drive the report-filing UI and are read on nearly every
    // mobile cold start, so they belong in the public-read mix.
    get(`${API_BASE}/issue-categories?page=0&size=${PAGE_SIZE}`, {
      name: 'GET /issue-categories',
    });
  });

  // Model human think-time so we measure server latency, not a tight CPU spin.
  sleep(Math.random() * 1.5 + 0.5);
}

/**
 * Pulls the first item's `id`/`publicId` out of a paged ApiResponse, or null.
 * @param {import('k6/http').Response} res
 * @returns {?string}
 */
function firstId(res) {
  const d = data(res);
  if (Array.isArray(d) && d.length > 0) {
    return d[0].id || d[0].publicId || null;
  }
  return null;
}
