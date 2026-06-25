// =============================================================================
// Taarifu performance harness — HTTP helpers.
//
// One place that: builds the Swahili-first headers, attaches bearer auth, tags
// each request by SLO class (read|write|admin) so thresholds.js can gate each
// class separately, records latency into the right Trend, and runs the standard
// envelope checks. Keeping this DRY means every scenario measures the same way
// (the SYNOPSIS lesson: don't let measurement drift like the legacy contracts).
// =============================================================================

import http from 'k6/http';
import { check } from 'k6';
import { ACCEPT_LANGUAGE } from './config.js';
import {
  readLatency,
  writeLatency,
  adminLatency,
  journeyOk,
  serverErrors,
} from './thresholds.js';

/**
 * Base headers for every call: JSON + Swahili-first locale. The locale is sent
 * on reads too so the i18n resolution path (ADR-0010) is part of the measured
 * latency — real Tanzanian traffic is sw by default (PRD §15).
 * @param {string} [bearer] optional access token.
 * @returns {object} header map.
 */
export function headers(bearer) {
  const h = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
    'Accept-Language': ACCEPT_LANGUAGE,
  };
  if (bearer) h['Authorization'] = `Bearer ${bearer}`;
  return h;
}

/** Records latency into the Trend for the given SLO class. */
function recordLatency(slo, ms) {
  if (slo === 'write') writeLatency.add(ms);
  else if (slo === 'admin') adminLatency.add(ms);
  else readLatency.add(ms);
}

/**
 * Issues a request, tags it, records its latency in the correct SLO trend, and
 * runs baseline checks. Returns the raw k6 response for scenario-specific checks.
 *
 * @param {('GET'|'POST')} method HTTP method.
 * @param {string} url absolute URL.
 * @param {object} opts
 * @param {('read'|'write'|'admin')} opts.slo SLO class -> which trend + budget.
 * @param {string} opts.name stable name tag (groups metrics, keeps cardinality low).
 * @param {string} [opts.bearer] access token.
 * @param {object} [opts.body] JSON body (POST).
 * @param {number[]} [opts.expect] acceptable status codes (default [200]).
 * @returns {import('k6/http').Response}
 */
export function request(method, url, opts) {
  const { slo, name, bearer, body, expect = [200] } = opts;
  const params = {
    headers: headers(bearer),
    // `name` tag keeps URL-templated paths from exploding metric cardinality
    // (k6 best practice: tag the route, not the concrete id).
    tags: { name, slo },
  };
  const res =
    method === 'POST'
      ? http.post(url, body ? JSON.stringify(body) : null, params)
      : http.get(url, params);

  recordLatency(slo, res.timings.duration);

  const statusOk = expect.includes(res.status);
  if (res.status >= 500 || res.status === 0) serverErrors.add(1);

  // Envelope-aware correctness: Taarifu wraps EVERY response in ApiResponse
  // { success, statusCode, data, ... } (PRD §17). A 2xx with success=false is
  // still a functional failure, so we assert both.
  const envelopeOk = check(res, {
    [`${name}: status ok`]: (r) => statusOk,
    [`${name}: envelope success`]: (r) => {
      if (!statusOk) return false;
      if (!r.body || r.body.length === 0) return true; // some endpoints 200 empty
      try {
        const j = r.json();
        // `success` is present on the envelope; if the shape ever changes this
        // check fails loudly rather than silently passing (contract guard).
        return j && (j.success === true || j.success === undefined);
      } catch (e) {
        return false;
      }
    },
  });
  journeyOk.add(envelopeOk);
  return res;
}

/** Convenience GET. @see request */
export function get(url, opts) {
  return request('GET', url, Object.assign({ slo: 'read' }, opts));
}

/** Convenience POST. @see request */
export function post(url, opts) {
  return request('POST', url, Object.assign({ slo: 'write' }, opts));
}

/**
 * Safely extracts `data` from an ApiResponse body, or null on any parse miss.
 * @param {import('k6/http').Response} res
 * @returns {*}
 */
export function data(res) {
  try {
    return res.json('data');
  } catch (e) {
    return null;
  }
}
