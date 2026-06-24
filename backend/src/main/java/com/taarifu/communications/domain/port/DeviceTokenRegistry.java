package com.taarifu.communications.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * Inbound query/maintenance port the push adapter uses to resolve and prune a recipient's device tokens
 * (PRD §13, EI-5; ports-and-adapters, ADR-0004).
 *
 * <p>Responsibility: decouples the {@link PushSender} adapter (which knows FCM, not the schema) from the
 * device-token <i>registry</i> (which knows the schema, not FCM). The adapter asks "what live tokens can I
 * push to for this recipient?" and, on an FCM {@code UNREGISTERED}/{@code INVALID_ARGUMENT} response,
 * tells the registry "this token is dead — prune it." It exposes only {@code UUID}/{@code String} — no
 * entity, no repository — so the {@code infrastructure} adapter never reaches into {@code domain}
 * internals (ARCHITECTURE §3.2).</p>
 *
 * <p><b>Secret handling (PRD §18)</b>: the returned tokens are sensitive routing credentials; an
 * implementation and every caller must treat them as secrets — never log the token string (log presence/
 * count only). They are intra-module values and never cross a module boundary or enter an event/DTO.</p>
 *
 * <p>WHY a port in {@code domain.port} (not the {@code application.service} impl directly): the adapter
 * lives in {@code infrastructure} and must depend on an abstraction, not the concrete service, so the
 * registry could be swapped (e.g. a cache-backed read model — ARCHITECTURE §10) without touching the
 * adapter (DIP). The impl is the {@code DeviceTokenService} {@code @Service}.</p>
 */
public interface DeviceTokenRegistry {

    /**
     * Resolves the live push tokens for a recipient profile.
     *
     * @param recipientProfileId the recipient profile's public id.
     * @return the recipient's live device tokens (the secret token strings), newest registration first;
     *         empty when none — the dispatcher then falls back to SMS while FEED is retained (EI-5).
     */
    List<String> tokensFor(UUID recipientProfileId);

    /**
     * Prunes a single token the push provider reported permanently invalid (FCM
     * {@code UNREGISTERED}/{@code INVALID_ARGUMENT}) — an idempotent soft-delete so a dead device stops
     * receiving fan-out and the history stays auditable (PRD §9).
     *
     * <p>WHY this is on the registry, not the adapter: the adapter holds no schema; pruning is a registry
     * maintenance concern. It is safe to call for an already-pruned/unknown token (no-op) — the push path
     * calls it opportunistically while iterating tokens and must never fail on a redundant prune.</p>
     *
     * @param token the dead push token to soft-delete (secret; never logged).
     */
    void pruneInvalid(String token);
}
