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
 * references (reporting categories, geography areas, identity accounts, the report itself) are by
 * {@code UUID} id only — this module never imports a sibling's {@code domain}/{@code infrastructure}
 * (ARCHITECTURE.md §3.2). The cross-module linkages are wired per ADR-0013:</p>
 * <ul>
 *   <li><b>Synchronous reads ({@code responders → reporting}):</b> {@code reportId} existence via
 *       {@code reporting.api.ReportQueryApi}, {@code categoryId} existence via
 *       {@code reporting.api.IssueCategoryQueryApi}, and the responder-side case lifecycle via
 *       {@code reporting.api.ReportLifecycleApi} (ADR-0013 §4a).</li>
 *   <li><b>Asynchronous events (outbox):</b> consumes reporting's {@code REPORT_ROUTED} to create the OWNER
 *       assignment ({@code RoutingHandler}, D21) and emits {@code api.event.ResponderAssignedEvent}
 *       ({@code RESPONDER_ASSIGNED}) back so reporting closes the round-trip; an assignment-created
 *       analytics fact rides the same outbox.</li>
 *   <li><b>Owner → search:</b> pushes each organisation's public directory projection to
 *       {@code search.api.SearchIndexApi} on the write path, and re-pushes all of them on demand via the
 *       {@code OrganisationSearchBackfillSource} adapter ({@code search.domain.port.SearchBackfillSource},
 *       ADR-0017 §1).</li>
 * </ul>
 *
 * <p>Two PHASE-3 cross-module dependencies remain explicit (no port available yet): existence-validating a
 * {@code Responder}'s coverage-area ids needs a geography area-by-id query port, and binding an
 * {@code Organisation}'s admin account is a Phase-2/3 B2B feature gated behind {@code RESPONDER_ADMIN}
 * (validated via {@code identity.api.UserAdminQueryApi} when it lands). See the respective entity Javadoc.</p>
 */
package com.taarifu.responders;
