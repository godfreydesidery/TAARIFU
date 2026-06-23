package com.taarifu.admin.api.spi;

/**
 * One labelled count contributed by a module to the admin platform dashboard (M14, US-14.1, UC-H06).
 *
 * <p>Responsibility: a single, transport-neutral metric — a stable machine {@code key}, a human
 * {@code label}, and a non-negative {@code value} — that a {@link ModuleStatsProvider} returns and the
 * admin dashboard aggregates. Keeping the contract a flat {@code (key,label,value)} triple (rather than a
 * bespoke DTO per module) lets new modules add metrics without changing the admin module (open/closed,
 * CLAUDE.md §3) and keeps the cross-module surface a plain data record carrying no entity or vendor type
 * (ADR-0013 §1 — only DTOs/enums/UUIDs cross a module boundary).</p>
 *
 * <p>WHY a {@code long} value (not a typed metric): every dashboard tile the PRD names — reports by
 * status, open cases, reps onboarded, verification-queue depth, users by tier, flags pending — is a
 * <b>count</b>. A single numeric shape is the simplest contract that satisfies the requirement (KISS);
 * richer time-series/aggregations are a later increment behind the same provider seam.</p>
 *
 * @param key   a stable machine identifier, namespaced by module (e.g. {@code reporting.reports.OPEN},
 *              {@code identity.users.tier.T3}); clients branch on this, so it is append-only by
 *              convention and never localised.
 * @param label a short human label for display (the admin UI may also localise via {@code key}); never
 *              PII.
 * @param value the metric value; must be {@code >= 0} (a count). Providers return {@code 0}, never a
 *              negative, when a category is empty.
 */
public record ModuleStat(String key, String label, long value) {

    /**
     * Compact canonical constructor enforcing the non-null/ non-negative invariants so a malformed metric
     * can never reach the dashboard envelope.
     *
     * @throws IllegalArgumentException if {@code key} is null/blank or {@code value} is negative.
     */
    public ModuleStat {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ModuleStat key must be present");
        }
        if (value < 0) {
            throw new IllegalArgumentException("ModuleStat value must be non-negative (it is a count)");
        }
    }
}
