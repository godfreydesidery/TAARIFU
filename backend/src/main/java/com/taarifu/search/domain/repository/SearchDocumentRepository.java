package com.taarifu.search.domain.repository;

import com.taarifu.search.domain.model.SearchDocument;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the {@code search_document} discovery index (ADR-0017).
 *
 * <p>Responsibility: (1) the idempotent-upsert lookup ({@link #findLiveBySource}); (2) the ranked, filtered,
 * <b>visibility-gated</b> full-text query the {@code GET /search} endpoint runs ({@link #search}); and (3) the
 * author-level visibility-maintenance bulk update the moderation outbox handler runs ({@link #hideByAuthor}).
 * Default derived/JPQL reads honour the entity's {@code @SQLRestriction("deleted = false")}; the native FTS
 * query re-states {@code deleted = false} explicitly because {@code @SQLRestriction} is NOT applied to native
 * SQL.</p>
 */
public interface SearchDocumentRepository extends JpaRepository<SearchDocument, Long> {

    /**
     * Finds the single live projection for a source entity, keyed on its {@code (entityType, entityPublicId)} —
     * the lookup behind the idempotent upsert/remove (ADR-0017 §1). Returns at most one row (the live-scoped
     * unique index guarantees it). "Live" is implicit: the entity's {@code @SQLRestriction("deleted = false")}
     * scopes this derived query to non-tombstoned rows.
     *
     * <p>WHY the method name has no {@code Live}/{@code Source} prefix: Spring Data parses derived-query method
     * names property-by-property, so a {@code findLiveBySource…} name would make it hunt for a {@code source…}
     * property that does not exist. The clean call-site name is provided by the {@link #findLiveBySource}
     * default below, which delegates here.</p>
     *
     * @param entityType     the kind of entity.
     * @param entityPublicId the source aggregate's public id.
     * @return the live projection, or empty if none is indexed.
     */
    Optional<SearchDocument> findByEntityTypeAndEntityPublicId(SearchEntityType entityType,
                                                              UUID entityPublicId);

    /**
     * Convenience alias for {@link #findByEntityTypeAndEntityPublicId} with an intention-revealing name at the
     * call site ("find the single live projection for this source"). A default method, so Spring Data does not
     * try to derive a query from <i>its</i> name.
     *
     * @param entityType     the kind of entity.
     * @param entityPublicId the source aggregate's public id.
     * @return the live projection, or empty if none is indexed.
     */
    default Optional<SearchDocument> findLiveBySource(SearchEntityType entityType, UUID entityPublicId) {
        return findByEntityTypeAndEntityPublicId(entityType, entityPublicId);
    }

    /**
     * The ranked, filtered, visibility-gated full-text search (ADR-0017 §4).
     *
     * <p>Matches {@code search_vector} against {@code websearch_to_tsquery('simple', :q)} — {@code websearch}
     * parses lay user input (quoted phrases, {@code or}, {@code -term}) without throwing on malformed syntax,
     * robust for a low-literacy/feature-phone audience (PRD §14/§15); the {@code 'simple'} config matches the
     * generated column so the GIN index is used. Results are ordered by {@code ts_rank} descending. Optional
     * filters ({@code entityType}/{@code areaId}/{@code categoryId}) follow the established
     * {@code CAST(:p AS …) IS NULL OR col = :p} nullable-filter idiom.</p>
     *
     * <p><b>Visibility gate (the security predicate):</b> a row is returned only if it is {@code PUBLIC}, OR the
     * caller is staff ({@code :includeStaff = true}) — so a guest/citizen can never see a {@code STAFF} row
     * (it is filtered out of the result set, not 403'd per row — anti-enumeration, PRD §18, ADR-0017 §4). The
     * service sets {@code includeStaff} only when the authenticated caller holds a staff role.</p>
     *
     * @param query        the raw user search text (passed to {@code websearch_to_tsquery}); never blank.
     * @param entityType   optional {@code SearchEntityType} name filter, or {@code null} for all types.
     * @param areaId       optional area public id filter, or {@code null}.
     * @param categoryId   optional category public id filter, or {@code null}.
     * @param includeStaff {@code true} to also return {@code STAFF}-visibility rows (staff callers only).
     * @param pageable     bounded paging (size capped by {@code PageRequestFactory}); sort is fixed to rank.
     * @return a page of {@link SearchResultProjection}, most relevant first.
     */
    @Query(value = """
            SELECT
                d.entity_type      AS entityType,
                d.entity_public_id AS entityPublicId,
                d.title            AS title,
                d.snippet_sw       AS snippetSw,
                d.snippet_en       AS snippetEn,
                d.area_id          AS areaId,
                d.category_id      AS categoryId,
                ts_rank(d.search_vector, websearch_to_tsquery('simple', :query)) AS rank
            FROM search_document d
            WHERE d.deleted = FALSE
              AND d.search_vector @@ websearch_to_tsquery('simple', :query)
              AND (:includeStaff = TRUE OR d.visibility = 'PUBLIC')
              AND (CAST(:entityType AS varchar) IS NULL OR d.entity_type = CAST(:entityType AS varchar))
              AND (CAST(:areaId AS uuid) IS NULL OR d.area_id = CAST(:areaId AS uuid))
              AND (CAST(:categoryId AS uuid) IS NULL OR d.category_id = CAST(:categoryId AS uuid))
            ORDER BY rank DESC, d.id DESC
            """,
            countQuery = """
            SELECT count(*)
            FROM search_document d
            WHERE d.deleted = FALSE
              AND d.search_vector @@ websearch_to_tsquery('simple', :query)
              AND (:includeStaff = TRUE OR d.visibility = 'PUBLIC')
              AND (CAST(:entityType AS varchar) IS NULL OR d.entity_type = CAST(:entityType AS varchar))
              AND (CAST(:areaId AS uuid) IS NULL OR d.area_id = CAST(:areaId AS uuid))
              AND (CAST(:categoryId AS uuid) IS NULL OR d.category_id = CAST(:categoryId AS uuid))
            """,
            nativeQuery = true)
    Page<SearchResultProjection> search(@Param("query") String query,
                                        @Param("entityType") String entityType,
                                        @Param("areaId") UUID areaId,
                                        @Param("categoryId") UUID categoryId,
                                        @Param("includeStaff") boolean includeStaff,
                                        Pageable pageable);

    /**
     * Visibility maintenance: forces every live projection authored by one account down to {@code STAFF}
     * visibility — the effect when that account is suspended (ADR-0017 §3). Idempotent: rows already
     * {@code STAFF} are matched out, so a redelivered {@code MODERATION_SANCTION_APPLIED} event is a no-op.
     *
     * <p>WHY a bulk {@code @Modifying} update (not load-then-save): a prolific author may have many indexed
     * rows; one indexed UPDATE is far cheaper than N entity round-trips and is naturally idempotent. Native
     * SQL so it bypasses any per-row state — but it still scopes {@code deleted = FALSE} explicitly (the
     * {@code @SQLRestriction} does not apply to native SQL).</p>
     *
     * @param accountId the suspended author's account public id.
     * @return the number of rows hidden (0 if the author had no public indexed content).
     */
    @Modifying
    @Query(value = """
            UPDATE search_document
               SET visibility = 'STAFF'
             WHERE deleted = FALSE
               AND visibility = 'PUBLIC'
               AND authored_by_account_id = :accountId
            """, nativeQuery = true)
    int hideByAuthor(@Param("accountId") UUID accountId);

    /**
     * Counts the live (non-tombstoned) projection rows currently in the discovery index — the size of the
     * index, surfaced by the admin reindex status read ({@code GET /search/admin/reindex/status}, ADR-0017
     * follow-up "a one-off backfill job per owner").
     *
     * <p>WHY a dedicated counter (not the inherited {@link JpaRepository#count()}): {@code JpaRepository.count()}
     * counts <i>all</i> rows including soft-deleted tombstones (the {@code @SQLRestriction} is NOT applied to the
     * Spring Data {@code count()} primitive in the same way the operator expects for "rows discoverable now"). A
     * native count that re-states {@code deleted = FALSE} returns exactly the live index size the operator reads
     * to confirm a backfill populated the index, and never inflates it with removed rows.</p>
     *
     * @return the number of live {@code search_document} rows ({@code >= 0}).
     */
    @Query(value = "SELECT count(*) FROM search_document WHERE deleted = FALSE", nativeQuery = true)
    long countLive();
}
