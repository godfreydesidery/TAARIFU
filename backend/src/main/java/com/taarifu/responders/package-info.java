/**
 * Responder / Service-Provider directory module — M18 (PRD §24, D20/D21; ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: the directory of bodies that handle citizen reports — government agencies,
 * parastatals/utilities, banks, telecoms, civic orgs and private companies — and the model that routes
 * reports to them and assigns them. Generalises the legacy "Area Official"/"Organisation": a government
 * Area Official is simply staff of a {@code GOVERNMENT_AGENCY} organisation (§24.1).</p>
 *
 * <p>This module owns four aggregates (four-layer internal structure, ARCHITECTURE.md §3.3):</p>
 * <ul>
 *   <li>{@code Organisation} — the body, its type/status/verification/contacts.</li>
 *   <li>{@code Responder} — a handling capability of an organisation: handled categories, coverage
 *       (areas or nationwide), SLA policy.</li>
 *   <li>{@code RoutingRule} — category(/sub) → responder kind/sector, area-narrowed or citizen-selected,
 *       with a priority/fallback ladder (§24.2).</li>
 *   <li>{@code ResponderAssignment} — the multisectoral one-OWNER + collaborators link of a responder to
 *       a report (§24.3), referencing the report by id only (reporting is built in parallel).</li>
 * </ul>
 *
 * <p>Public surface: an unauthenticated provider directory ("who handles what", §24.1) and a
 * Moderator/Admin management surface (CRUD + verification + routing + assignment, §24.4). Cross-module
 * references (reporting categories, geography areas, identity users, the report itself) are by
 * {@code UUID} id only — this module does not import the parallel reporting/communications/tokens
 * modules; those linkages are a later wiring step (see {@code // TODO(wiring)} markers and the published
 * {@code api.event.ResponderAssignedEvent} seam).</p>
 */
package com.taarifu.responders;
