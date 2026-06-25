// =============================================================================
// SCENARIO 2 — Citizen happy path: (signup/OTP) -> file report -> track.
//
// This is the platform's core civic-write loop (PRD §10 Epic M3, UC-D01/D05):
//   1. (Onboarding) request a signup OTP, then verify it -> T1 account + tokens.
//   2. File an issue report (WRITE, T2-gated).
//   3. Track it: list my reports, fetch the report, fetch its timeline (READS).
//
// SLO classes: the file-report POST is a WRITE -> p95 < 1s (PRD §15). The track
// reads ride the READ budget (p95 < 500ms).
//
// -------------------------------------------------------------------------
// IMPORTANT — OTP and the tier gate (why this scenario needs seeded tokens):
// -------------------------------------------------------------------------
// * OtpService delivers the code OUT-OF-BAND and stores only its keyed HASH; the
//   plaintext OTP is NEVER returned by any API (S-4, anti-enumeration). A
//   black-box load script therefore cannot read the code from the signup
//   response. We expose TWO honest modes, selected by env:
//
//   AUTH_MODE=seeded (DEFAULT, recommended for the real SLO numbers):
//     The per-VU loop uses a PRE-SEEDED bearer token (CITIZEN_BEARER or a row
//     from CITIZEN_TOKENS_CSV) for a >= T2 account. Filing is T2-gated
//     (ReportController @RequiresTier("T2")), so a fresh T1 signup CANNOT file —
//     seeded T2 tokens are the correct way to load-test the write path.
//     The onboarding OTP round-trip is still exercised once per VU as a SETUP
//     warm-call (measured, but it can't complete signup without the code).
//
//   AUTH_MODE=otp-stub (only if the team adds a perf-only OTP test seam):
//     If the backend is run with a documented perf seam that returns/uses a
//     fixed OTP (e.g. a STUB OtpService bean behind a `perf` Spring profile, or
//     a debug endpoint that returns the challenge code in NON-production), set
//     PERF_OTP=<fixed-code> and the loop will complete the full signup. We do
//     NOT touch app source to add this seam; it is documented in the README as a
//     backend change the team can opt into.
//
// Run:
//   k6 run -e PROFILE=load -e CITIZEN_BEARER=<t2-token> perf/k6/scenarios/citizen-journey.js
//   k6 run -e PROFILE=smoke -e AUTH_MODE=otp-stub -e PERF_OTP=000000 ...
// =============================================================================

import { sleep, group, fail } from 'k6';
import { SharedArray } from 'k6/data';
import {
  API_BASE,
  CITIZEN_BEARER,
  CITIZEN_TOKENS_CSV,
  PAGE_SIZE,
  hasCitizenAuth,
} from '../lib/config.js';
import { get, post, request, data } from '../lib/http.js';
import { buildThresholds } from '../lib/thresholds.js';
import { rampingScenario } from '../lib/profiles.js';
import { setupReportRefs } from '../lib/setup.js';

const AUTH_MODE = __ENV.AUTH_MODE || 'seeded';
const PERF_OTP = __ENV.PERF_OTP || '';

// Load the optional token pool ONCE, shared across all VUs (SharedArray keeps a
// single copy in memory, not one per VU — important at high VU counts).
const TOKEN_POOL = new SharedArray('citizen-tokens', () => {
  if (!CITIZEN_TOKENS_CSV) return CITIZEN_BEARER ? [CITIZEN_BEARER] : [];
  // open() reads the file relative to the script's location at init time.
  const raw = open(CITIZEN_TOKENS_CSV);
  return raw
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !l.startsWith('#'));
});

export const options = {
  scenarios: { citizen_journey: rampingScenario('citizenJourney') },
  // Both write (file report) and read (track) budgets apply here.
  thresholds: buildThresholds({ write: true, read: true }),
};

/**
 * setup(): readiness gate + resolve the categoryId + wardId a report must carry
 * (FileReportDto requires both). Also validates we have a way to authenticate.
 * @returns {{categoryId:?string, wardId:?string}}
 */
export function setup() {
  const refs = setupReportRefs();
  if (AUTH_MODE === 'seeded' && !hasCitizenAuth()) {
    fail(
      'AUTH_MODE=seeded requires a >= T2 token. Set CITIZEN_BEARER=<token> or ' +
        'CITIZEN_TOKENS_CSV=<file>. See perf/README.md "Seeding" for how to mint one.'
    );
  }
  if (AUTH_MODE === 'otp-stub' && !PERF_OTP) {
    fail(
      'AUTH_MODE=otp-stub requires PERF_OTP=<fixed-code> AND a backend perf OTP ' +
        'seam. See perf/README.md "Citizen write path".'
    );
  }
  if (!refs.categoryId || !refs.wardId) {
    // We can still run onboarding, but not the write; warn loudly via a failing
    // setup so the operator seeds reference geography + categories first.
    fail(
      `Could not resolve a categoryId (${refs.categoryId}) and wardId (${refs.wardId}) ` +
        'from public reads. Seed reference geography + issue categories, or pass ' +
        'REPORT_CATEGORY_ID / REPORT_WARD_ID. See perf/README.md.'
    );
  }
  return refs;
}

/**
 * One full citizen iteration.
 * @param {{categoryId:string, wardId:string}} ctx setup output.
 */
export function citizenJourney(ctx) {
  // -- Onboarding (always exercised; completes only in otp-stub mode) ---------
  const bearer = group('onboarding', () => onboard());

  // Without a usable bearer (seeded pool empty or otp-stub failed) we cannot do
  // the authenticated write/track. The setup() guard makes this unreachable in
  // a correctly-configured run, but we fail-safe rather than 401-spam.
  if (!bearer) {
    fail('No usable bearer token for the citizen write path.');
  }

  // -- File a report (WRITE, T2-gated) ---------------------------------------
  let reportId = null;
  group('file-report', () => {
    const res = post(`${API_BASE}/reports`, {
      name: 'POST /reports',
      bearer,
      expect: [201],
      body: {
        categoryId: ctx.categoryId,
        title: `Mtaro umeziba - load test ${Date.now()}`,
        description:
          'Taarifu ya majaribio ya mzigo: mtaro umeziba na maji yamejaa barabarani. ' +
          'Hii ni rekodi ya k6 load test, si tukio halisi.',
        wardId: ctx.wardId,
        anonymous: false,
        attachmentRefs: [],
      },
    });
    reportId = pickId(res);
  });

  // -- Track it (READS) ------------------------------------------------------
  group('track', () => {
    // List my reports (US-3.2).
    get(`${API_BASE}/reports?page=0&size=${PAGE_SIZE}`, {
      name: 'GET /reports (mine)',
      bearer,
    });
    if (reportId) {
      get(`${API_BASE}/reports/${reportId}`, {
        name: 'GET /reports/{id}',
        bearer,
      });
      get(`${API_BASE}/reports/${reportId}/timeline?page=0&size=${PAGE_SIZE}`, {
        name: 'GET /reports/{id}/timeline',
        bearer,
      });
    }
  });

  sleep(Math.random() * 2 + 1); // citizen think-time between actions
}

/**
 * Performs the onboarding step and returns a usable bearer token (or null).
 * In seeded mode it returns a token from the pool and exercises the OTP-request
 * call once as a measured warm path. In otp-stub mode it runs the full
 * request -> verify signup against the perf OTP seam.
 * @returns {?string}
 */
function onboard() {
  const phone = randomPhone();

  if (AUTH_MODE === 'otp-stub') {
    // Full signup using the documented perf OTP seam (NON-production only).
    const reqRes = request('POST', `${API_BASE}/auth/otp/request`, {
      slo: 'write',
      name: 'POST /auth/otp/request',
      expect: [202],
      body: { phone },
    });
    const challengeId = (data(reqRes) || {}).challengeId;
    if (!challengeId) return null;
    const signupRes = request('POST', `${API_BASE}/auth/signup`, {
      slo: 'write',
      name: 'POST /auth/signup',
      expect: [201],
      body: { challengeId, code: PERF_OTP },
    });
    const body = data(signupRes) || {};
    return body.tokens ? body.tokens.accessToken : null;
  }

  // seeded mode: exercise the OTP-request hop (measured) but DON'T attempt to
  // verify (we can't read the code); then return a real >= T2 token for writes.
  request('POST', `${API_BASE}/auth/otp/request`, {
    slo: 'write',
    name: 'POST /auth/otp/request',
    expect: [202],
    body: { phone },
  });
  return pickToken();
}

/** Picks a bearer from the shared pool round-robin-ish by VU iteration. */
function pickToken() {
  if (TOKEN_POOL.length === 0) return null;
  // __VU/__ITER spread VUs across the pool to avoid all VUs sharing one account
  // (one-account-per-person realism + avoids per-account rate limits skewing).
  const idx = (__VU + __ITER) % TOKEN_POOL.length;
  return TOKEN_POOL[idx];
}

/** Extracts the created report's public id from the ApiResponse. */
function pickId(res) {
  const d = data(res);
  return d ? d.id || d.publicId || null : null;
}

/** Builds a valid E.164 TZ phone (+255...) unique-ish per call. */
function randomPhone() {
  const n = Math.floor(Math.random() * 900000000) + 100000000; // 9 digits
  return `+255${n}`;
}
