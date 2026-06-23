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
 */
package com.taarifu.engagement;
