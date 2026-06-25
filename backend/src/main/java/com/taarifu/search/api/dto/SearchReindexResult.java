package com.taarifu.search.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * The result of one admin-triggered reindex/backfill run ({@code POST /search/admin/reindex}, ADR-0017 backfill
 * follow-up) — what the orchestrator did across every registered
 * {@link com.taarifu.search.domain.port.SearchBackfillSource}.
 *
 * <p>Responsibility: give the operator a single, PII-free receipt — how many sources ran, total rows upserted,
 * the live index size after the run, the per-source breakdown, and timing — so they can confirm the index was
 * populated and spot any source that failed (the run is idempotent and safe to repeat). Counts/ids/timestamps
 * only; never any corpus or PII (PRD §18).</p>
 *
 * @param startedAt    when the run began (UTC).
 * @param finishedAt   when the run completed (UTC).
 * @param sourcesRun   the number of registered backfill sources driven this run.
 * @param totalUpserted the total rows upserted across all sources this run ({@code >= 0}).
 * @param indexSize    the live index row count after the run (from {@code countLive()}).
 * @param allSucceeded {@code true} if every source completed; {@code false} if any failed (see {@code sources}).
 * @param sources      the per-source breakdown (never {@code null}; empty when no source is registered yet —
 *                     the expected state until owners ship their adapters, see CENTRAL NEEDS).
 */
public record SearchReindexResult(
        Instant startedAt,
        Instant finishedAt,
        int sourcesRun,
        long totalUpserted,
        long indexSize,
        boolean allSucceeded,
        List<SearchReindexSourceResult> sources
) {
}
