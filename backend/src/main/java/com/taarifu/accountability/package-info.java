/**
 * accountability module — M6 Representative Accountability (PRD §10 Epic M6; §23 civic-integrity fence;
 * ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility: representative accountability data and binding ratings —
 * {@link com.taarifu.accountability.domain.model.RepresentativeContribution} (speeches/motions/bills/
 * questions/votes/committee), {@link com.taarifu.accountability.domain.model.Attendance},
 * {@link com.taarifu.accountability.domain.model.Promise}, and the binding
 * {@link com.taarifu.accountability.domain.model.Rating}. Contributions/attendance/promises are
 * <b>curated</b> (D-Q4, {@code ROLE_ADMIN}) and read publicly; ratings are a binding civic action behind
 * the integrity fence.</p>
 *
 * <p>Layered api/application/domain per the canonical layout. <b>Integrity invariants enforced here
 * (D13/D16/D18, §23):</b> rating a representative requires live T3 ({@code @RequiresTier}), is one per
 * (rater, subject, period) (DB unique), forbids self-rating ({@code ScopeGuard.isNotSelf}), enforces the
 * two-tier electoral scope (an elector of the rep's constituency or ward, via institutions'
 * {@code RepresentativeQueryApi} × identity's {@code ElectoralScopeApi}), and NEVER reads a token balance
 * — wealth cannot buy democratic weight; the aggregate score is computed from append-only rows. The
 * curated-authoring path additionally validates the referenced representative exists
 * ({@code RepresentativeQueryApi.exists}) before persisting any contribution/attendance/promise.</p>
 *
 * <p>Module isolation: the rated/owning subjects (representative, project, rater profile) live in other
 * modules (institutions/projects/identity) and are referenced by public {@code UUID} only — never an FK
 * and never an import of those feature modules. Cross-module resolution goes through the callee's
 * published {@code ..api..} port only (ADR-0013): the representative existence/electoral seat via
 * {@code institutions.api.RepresentativeQueryApi}, the rater's electoral scope via
 * {@code identity.api.ElectoralScopeApi}. ({@code linkedProjectIds} on a promise are not yet validated —
 * no projects module/port exists; that remains a documented {@code // TODO(wiring)}.) Only
 * {@code com.taarifu.common}, {@code com.taarifu.geography}, and {@code com.taarifu.identity} (the merged
 * upstream) plus published api ports may be imported.</p>
 */
package com.taarifu.accountability;
