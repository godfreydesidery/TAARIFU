package com.taarifu.search.application.service;

import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchReindexResult;
import com.taarifu.search.api.dto.SearchReindexSourceResult;
import com.taarifu.search.api.dto.SearchReindexStatus;
import com.taarifu.search.domain.port.SearchBackfillSource;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the admin-triggered <b>reindex/backfill</b> that (re)populates the discovery index from data that
 * pre-dates the producers being wired (ADR-0017 follow-up "a one-off backfill job per owner").
 *
 * <p>Responsibility: discover every owning module's {@link SearchBackfillSource} adapter (Spring injects all
 * implementations), drive each to re-push its own public projections through {@link SearchIndexApi}, accumulate
 * a PII-free per-source receipt, and remember the last run for the status read. It owns NO projection or privacy
 * logic — that lives in each source (the owner reuses its live producer's fence, so the privacy decision is never
 * duplicated here and search never reaches into a sibling's internals, ADR-0013 §1).</p>
 *
 * <p><b>Idempotent (safe to re-run):</b> every source upserts by {@code (entityType, publicId)}, so a second run
 * lands the same live rows in place — never a duplicate. This is the same idempotency the live
 * {@link SearchIndexService#upsert} guarantees; the backfill adds no new write path, it just replays the
 * owners' existing one in bulk.</p>
 *
 * <p><b>Fault isolation:</b> each source runs in its own try/catch — one owner throwing (an outage mid-page)
 * records a failed {@link SearchReindexSourceResult} for that source and the job continues, so a single source's
 * failure degrades that entity type's coverage only, never the whole reindex. The operator re-runs after the fix
 * (idempotent).</p>
 *
 * <p><b>🔒 PII discipline (PRD §18):</b> this service never touches the corpus — it passes the index port down to
 * each source and reads back counts only. It logs entity-type + counts only (never a title/snippet/author id).
 * The {@code error} captured on a source failure is the exception's message class/text trimmed to a short reason,
 * which carries no PII (the owners log their own detail).</p>
 *
 * <p><b>NOT transactional at this level:</b> there is deliberately no class/method {@code @Transactional} —
 * each source's {@code upsert} calls already open their own short transactions ({@link SearchIndexService}), and a
 * full backfill must not run as one giant transaction (it would hold a connection across the whole corpus and a
 * late failure would roll back hours of work). Per-row idempotent upserts are the correct unit of recovery.</p>
 */
@Service
public class SearchBackfillService {

    private static final Logger log = LoggerFactory.getLogger(SearchBackfillService.class);

    /** Cap a captured failure reason to a short, PII-free string for the receipt. */
    private static final int MAX_ERROR_LEN = 200;

    private final List<SearchBackfillSource> sources;
    private final SearchIndexApi indexApi;
    private final SearchDocumentRepository repository;

    /**
     * The most recent run's result, held in memory for the status read. {@code volatile} so the status read sees a
     * consistent reference written by the (single-flight) reindex thread; {@code null} until the first run since
     * boot. Not persisted — it is an operational hint, not the audit record (the run also emits the durable trace).
     */
    private volatile SearchReindexResult lastRun;

    /**
     * @param sources    every owning module's backfill adapter — Spring injects all {@link SearchBackfillSource}
     *                   beans (empty list until owners ship them; the job then no-ops cleanly — CENTRAL NEEDS).
     * @param indexApi   the search module's own inbound index port the sources upsert through.
     * @param repository the index persistence port (for the live-row count in the receipt/status).
     */
    public SearchBackfillService(List<SearchBackfillSource> sources,
                                 SearchIndexApi indexApi,
                                 SearchDocumentRepository repository) {
        this.sources = sources;
        this.indexApi = indexApi;
        this.repository = repository;
    }

    /**
     * Runs a full reindex/backfill across every registered source and returns a PII-free receipt.
     *
     * <p>Drives each source's {@link SearchBackfillSource#backfill(SearchIndexApi)} with fault isolation, totals
     * the upserts, reads the resulting live index size, records the run as {@link #lastRun}, and returns the
     * breakdown. With zero registered sources the run is a clean no-op (0 sources, 0 upserts) — the expected
     * state until owners ship adapters.</p>
     *
     * @return the run receipt (per-source counts, totals, post-run index size, timing).
     */
    public SearchReindexResult reindexAll() {
        Instant startedAt = Instant.now();
        log.info("Search reindex/backfill starting across {} registered source(s)", sources.size());

        List<SearchReindexSourceResult> results = new ArrayList<>(sources.size());
        long totalUpserted = 0L;
        boolean allSucceeded = true;

        for (SearchBackfillSource source : sources) {
            String type = source.entityType().name();
            try {
                long upserted = source.backfill(indexApi);
                results.add(SearchReindexSourceResult.ok(type, upserted));
                totalUpserted += upserted;
                log.info("Reindex source {} upserted {} row(s)", type, upserted);
            } catch (RuntimeException ex) {
                // Fault isolation: one source's failure does not abort the rest. Capture a short, PII-free
                // reason only (the owner logs its own detail) and keep going (PRD §18, fail-safe ARCHITECTURE).
                allSucceeded = false;
                String reason = shortReason(ex);
                results.add(SearchReindexSourceResult.failed(type, 0L, reason));
                log.warn("Reindex source {} FAILED: {}", type, reason);
            }
        }

        long indexSize = repository.countLive();
        SearchReindexResult result = new SearchReindexResult(
                startedAt, Instant.now(), sources.size(), totalUpserted, indexSize, allSucceeded, results);
        this.lastRun = result;
        log.info("Search reindex/backfill finished: {} source(s), {} upserted, index size now {} (allSucceeded={})",
                sources.size(), totalUpserted, indexSize, allSucceeded);
        return result;
    }

    /**
     * Returns the current index status — the live index size now, the number of registered sources, and the last
     * run's receipt (or {@code null} if none has run since boot).
     *
     * @return a fresh {@link SearchReindexStatus} snapshot.
     */
    public SearchReindexStatus status() {
        return new SearchReindexStatus(repository.countLive(), sources.size(), lastRun);
    }

    /**
     * Reduces a thrown exception to a short, PII-free reason string for the receipt — the exception's simple class
     * name plus its message, trimmed to {@link #MAX_ERROR_LEN}. Owners' projections carry no PII, but we trim
     * defensively and never include a stack trace in the API response.
     *
     * @param ex the caught exception.
     * @return a short reason string (never {@code null}).
     */
    private static String shortReason(RuntimeException ex) {
        String msg = ex.getMessage();
        String reason = ex.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        return reason.length() <= MAX_ERROR_LEN ? reason : reason.substring(0, MAX_ERROR_LEN);
    }
}
