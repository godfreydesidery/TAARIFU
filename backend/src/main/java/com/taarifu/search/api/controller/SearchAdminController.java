package com.taarifu.search.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.search.api.dto.SearchReindexResult;
import com.taarifu.search.api.dto.SearchReindexStatus;
import com.taarifu.search.application.service.SearchBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The back-office <b>reindex/backfill</b> ops surface for the discovery index (ADR-0017 follow-up "a one-off
 * backfill job per owner" + "a count/last-run status read").
 *
 * <p>Responsibility: a thin HTTP layer over {@link SearchBackfillService} that lets an operator (re)populate the
 * {@code search_document} index from data that pre-dates the producers being wired, and read the current index
 * status. It holds no business logic and no {@code @Transactional} (ARCHITECTURE §3.3); the orchestration,
 * fault isolation, and idempotency live in the service.</p>
 *
 * <p><b>Authorization (deny-by-default, ARCHITECTURE §6.2; PRD §7.1, §18):</b> {@code hasAnyRole('ADMIN','ROOT')}
 * on every method. A reindex re-reads every owner's public corpus and rewrites the discovery surface — it is a
 * high-trust back-office power, so a citizen/responder/moderator token is forbidden (403) and an anonymous
 * request is unauthenticated (401). <b>Note on URL filtering:</b> {@code /search/**} GET is centrally
 * {@code permitAll()} for the public discovery read (SecurityConfig), so {@code GET …/reindex/status} passes the
 * URL filter — the {@code @PreAuthorize} method gate is what actually restricts it to staff (admin surfaces are
 * gated by method security, never by URL — SecurityConfig §42). {@code POST …/reindex} is not a public pattern,
 * so it requires both authentication and the staff role. The security test fails closed if a {@code @PreAuthorize}
 * were removed.</p>
 *
 * <p><b>Staff second factor (MFA):</b> upstream — an {@code ADMIN}/{@code ROOT} access token is only minted after
 * the TOTP step (AUTH-DESIGN §14.1), so reaching these handlers already implies MFA was satisfied (identical to
 * the other admin surfaces).</p>
 *
 * <p><b>🔒 Privacy (PRD §18):</b> both responses carry counts/timing/entity-type names only — never any indexed
 * title, snippet, author id, or PII. The reindex never indexes a private/anonymous/sensitive/unpublished row:
 * each source reuses its live producer's visibility fence (ADR-0017 §1), so this endpoint cannot widen exposure.</p>
 */
@RestController
@RequestMapping("/search/admin/reindex")
@Tag(name = "Search Admin", description = "Back-office reindex/backfill of the discovery index + status "
        + "(admin/root; ADR-0017).")
public class SearchAdminController {

    private final SearchBackfillService backfillService;
    private final ResponseFactory responses;

    /**
     * @param backfillService the reindex orchestration service.
     * @param responses       envelope builder.
     */
    public SearchAdminController(SearchBackfillService backfillService, ResponseFactory responses) {
        this.backfillService = backfillService;
        this.responses = responses;
    }

    /**
     * Triggers a full reindex/backfill across every registered owning-module source, returning a PII-free receipt.
     *
     * <p>Idempotent: safe to re-run (every source upserts by source key — no duplicates). With no source wired yet
     * the result is a clean no-op (0 sources, 0 upserted) — the expected state until owners ship adapters
     * (CENTRAL NEEDS).</p>
     *
     * @return {@code 200} + the {@link SearchReindexResult} run receipt.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Reindex/backfill the discovery index (admin/root)",
            description = "Re-pushes every owning module's public, PII-free projections into the index. "
                    + "Idempotent (upsert by source key); respects visibility exactly like the live producers "
                    + "(never indexes private/anonymous/sensitive/unpublished). Returns a per-source receipt.")
    public ApiResponse<SearchReindexResult> reindex() {
        return responses.ok(backfillService.reindexAll());
    }

    /**
     * Reads the current index status — the live index size now, the number of registered backfill sources, and
     * the last run's receipt (or {@code null} {@code lastRun} if none has run since boot).
     *
     * @return {@code 200} + the {@link SearchReindexStatus} snapshot.
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMIN','ROOT')")
    @Operation(summary = "Discovery index reindex status (admin/root)",
            description = "Live index size, number of registered backfill sources, and the last run's receipt "
                    + "(in-memory; resets on restart). Counts/timing only — no corpus or PII.")
    public ApiResponse<SearchReindexStatus> status() {
        return responses.ok(backfillService.status());
    }
}
