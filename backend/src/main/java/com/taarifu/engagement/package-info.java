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
 * enforcement (D13) is wired via institutions' {@code RepresentativeQueryApi} × identity's
 * {@code ElectoralScopeApi}; the account→Profile author resolution is wired via identity's
 * {@link com.taarifu.identity.api.ProfileLookupApi} on the create paths — all {@code api → api}, no import.</p>
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
 * <p><b>Search discovery indexing (ADR-0017 §1, ADR-0013 §1 owner→search — DONE):</b> on
 * create/activate/sign/answer/open and every lifecycle change, engagement pushes a public, PII-free projection
 * (public title/summary only, never a DRAFT, private body, signer/asker/responder, or any PII) via
 * {@link com.taarifu.search.api.SearchIndexApi#upsert}/{@code remove}. The search module's
 * {@link com.taarifu.search.domain.model.enums.SearchEntityType} now carries the
 * {@code PETITION}/{@code POLL}/{@code QUESTION} values (the search owner's additive change, ADR-0017 §2), so
 * each service maintains its discovery row through a single {@code reindexForDiscovery} fence.</p>
 *
 * <p><b>Search backfill (ADR-0017 follow-up "a one-off backfill job per owner"; ADR-0013 §7 /
 * ModuleBoundaryTest carve-out (b) — DONE):</b> engagement implements the search module's
 * {@link com.taarifu.search.domain.port.SearchBackfillSource} <b>domain.port</b> three times (one per owned
 * {@code SearchEntityType}) in {@code application.service.search} —
 * {@link com.taarifu.engagement.application.service.search.PetitionBackfillSource},
 * {@link com.taarifu.engagement.application.service.search.SurveyBackfillSource}, and
 * {@link com.taarifu.engagement.application.service.search.QuestionBackfillSource}. Each pages its own
 * publicly-visible (non-DRAFT / OPEN-or-ANSWERED) rows and re-pushes the <i>same</i> projection the live
 * producer builds (it calls the very same {@code reindexForDiscovery} method, so the visibility fence cannot
 * drift), idempotently via {@code upsert}. The search reindex orchestrator auto-discovers these beans. This is
 * the sanctioned cross-module {@code domain.port} injection pattern (the {@code SmsGateway}/{@code Geocoder}
 * shape), NOT a boundary violation — engagement implements a port search published, importing only search's
 * {@code api} + {@code domain.port} + {@code domain.model.enums}, never search's
 * {@code domain.model}/{@code infrastructure}.</p>
 */
package com.taarifu.engagement;
