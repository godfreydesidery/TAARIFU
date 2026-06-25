package com.taarifu.search.api.dto;

/**
 * The per-source outcome of one admin reindex/backfill run — one row per registered
 * {@link com.taarifu.search.domain.port.SearchBackfillSource} (ADR-0017 backfill follow-up).
 *
 * <p>Responsibility: tell the operator, for each searchable entity type, how many rows that source upserted and
 * whether it succeeded — so a partial failure (one owner's source errored while the rest completed) is visible
 * and the operator can re-run safely (the job is idempotent). It carries counts + an outcome flag only; on
 * failure the {@code error} is a short, PII-free reason string (never a stack trace or any corpus/PII — PRD §18).</p>
 *
 * @param entityType the source's entity type name (e.g. {@code REPRESENTATIVE}).
 * @param upserted   the number of rows this source pushed into the index this run ({@code >= 0}).
 * @param succeeded  {@code true} if the source completed; {@code false} if it threw (its rows are partial).
 * @param error      a short PII-free failure reason when {@code !succeeded}, else {@code null}.
 */
public record SearchReindexSourceResult(
        String entityType,
        long upserted,
        boolean succeeded,
        String error
) {
    /**
     * Builds a success row.
     *
     * @param entityType the source's entity type name.
     * @param upserted   the rows upserted.
     * @return a succeeded result with no error.
     */
    public static SearchReindexSourceResult ok(String entityType, long upserted) {
        return new SearchReindexSourceResult(entityType, upserted, true, null);
    }

    /**
     * Builds a failure row (partial rows for this source).
     *
     * @param entityType the source's entity type name.
     * @param upserted   the rows upserted before the failure ({@code >= 0}).
     * @param error      a short PII-free reason.
     * @return a failed result.
     */
    public static SearchReindexSourceResult failed(String entityType, long upserted, String error) {
        return new SearchReindexSourceResult(entityType, upserted, false, error);
    }
}
