/**
 * engagement module — petitions, surveys/polls, and public Q&A (PRD §9.1, §12.2 M8/M9/M10;
 * ARCHITECTURE.md §3.1).
 *
 * <p>Responsibility (Phase-2 scaffold): the civic-participation aggregates —
 * {@link com.taarifu.engagement.domain.model.Petition} + {@link com.taarifu.engagement.domain.model.PetitionSignature},
 * {@link com.taarifu.engagement.domain.model.Survey} + {@link com.taarifu.engagement.domain.model.SurveyResponse},
 * and {@link com.taarifu.engagement.domain.model.Question} + {@link com.taarifu.engagement.domain.model.Answer} —
 * with their read/create endpoints and the binding actions (sign petition, respond to a binding poll).</p>
 *
 * <p>Four-layer internal structure ({@code api}/{@code application}/{@code domain}/{@code infrastructure})
 * per ARCHITECTURE §3.3. It depends on the shared kernel ({@code common}) only; all cross-module
 * references (the petition/question target in <b>institutions</b>, the creator/signer/asker in
 * <b>identity</b>) are by {@code UUID} public id, never by import or FK (modular-monolith boundary,
 * ARCHITECTURE §3.2; HARD ISOLATION rule 2).</p>
 *
 * <p><b>Civic-integrity fence (D18, PRD §23.5):</b> binding actions are gated by tier
 * ({@code @RequiresTier}) + no-self-action ({@link com.taarifu.common.security.ScopeGuard}) +
 * one-per-person (DB unique constraints) and <b>never</b> read a token balance. Electoral-scope
 * enforcement (D13) and the account→Profile / target-validation cross-module resolution are wired in a
 * later integration step (marked {@code // TODO(wiring)} in the services).</p>
 *
 * <p><b>Moderation auto-assist (ADR-0018; ADR-0013 §4c — DONE):</b> engagement publishes
 * {@link com.taarifu.moderation.api.SubjectContentQueryApi} implementations for the
 * {@link com.taarifu.moderation.api.FlagSubjectType} values it owns —
 * {@link com.taarifu.engagement.application.service.PetitionSubjectContentQuery} ({@code PETITION}) and
 * {@link com.taarifu.engagement.application.service.QuestionSubjectContentQuery} ({@code QUESTION}) — so a
 * flagged petition / Q&amp;A thread can be auto-scored (hold-and-prioritise only; the human pipeline is the
 * floor). Moderation auto-discovers these beans via its {@code SubjectContentResolver}; the returned text is
 * transient (never persisted/logged) and PII-free. The {@code COMMENT} {@code FlagSubjectType} has <b>no
 * standalone moderatable entity in this module</b> (a petition-signature comment is a sub-field of a signature,
 * not its own flaggable subject), so no {@code COMMENT} content port is published — an unregistered subject
 * type resolves to empty and degrades to a human screen by design.</p>
 *
 * <p><b>// TODO(wiring) — search discovery indexing (ADR-0017 §1; BLOCKED on a CENTRAL NEED):</b> on
 * create/update/close/delete of petitions, polls/surveys, and Q&amp;A questions, engagement must push a
 * public projection (public title/summary + area/category facets only, never a DRAFT, private body, or
 * anonymous author) via {@link com.taarifu.search.api.SearchIndexApi#upsert}/{@code remove}. This wiring is
 * <b>blocked</b> because the search module's {@link com.taarifu.search.domain.model.enums.SearchEntityType}
 * (the {@code entityType} of {@link com.taarifu.search.api.dto.SearchDocumentUpsert}) has no values for
 * engagement content — it covers only {@code REPRESENTATIVE}/{@code ORGANISATION}/{@code ANNOUNCEMENT}/
 * {@code ISSUE_CATEGORY}/{@code PUBLIC_REPORT}. Adding {@code PETITION}/{@code POLL}/{@code QUESTION} values is
 * an additive change the <b>search-module owner</b> must make (ADR-0017 §2 "adding a searchable entity adds a
 * value here"); engagement must not edit another module's {@code domain} (parallel-build isolation). Once the
 * enum values land, this wiring is a one-call-per-lifecycle step on the create/update/close/delete paths.</p>
 */
package com.taarifu.engagement;
