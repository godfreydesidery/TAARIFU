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
 *
 * <p><b>Published-port contributions (ADR-0013 §4c / ADR-0018):</b> accountability owns the only flaggable
 * free text in its surface — a {@code Rating}'s optional comment (US-6.2, moderated downstream) — so it
 * <b>implements</b> moderation's published {@code moderation.api.SubjectContentQueryApi} for
 * {@code FlagSubjectType.RATING} ({@code RatingSubjectContentQuery}), surfacing that comment transiently to
 * moderation's auto-assist scorer. This is a feature→foundation {@code api → api} dependency (moderation
 * owns the interface; accountability provides the impl — dependency inversion, no cycle); the comment is
 * never persisted or logged by moderation (PRD §18, PDPA). Accountability is <b>not</b> a search index
 * producer: a rating/promise has no {@code SearchEntityType} (it is a binding civic action / moderated
 * comment, not a public directory entity), so nothing here pushes to {@code search.api.SearchIndexApi} —
 * the directory entities that are indexed (representatives, organisations) are owned by institutions and
 * responders respectively.</p>
 */
package com.taarifu.accountability;
