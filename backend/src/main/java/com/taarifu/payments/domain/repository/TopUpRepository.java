package com.taarifu.payments.domain.repository;

import com.taarifu.payments.domain.model.TopUp;
import com.taarifu.payments.domain.model.enums.MobileMoneyProvider;
import com.taarifu.payments.domain.model.enums.TopUpStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link TopUp} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: the lookups the top-up flow needs — by public id (status endpoint), by idempotency key
 * (initiate dedup), and by {@code (provider, provider_ref)} (webhook reconciliation), plus a
 * pessimistically-locked load by provider reference used by the reconciliation path. It also serves the
 * scheduled reconciliation job (a locked claim of stale non-terminal rows) and the ADMIN payment query
 * (filtered, paged search + totals). Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface TopUpRepository extends JpaRepository<TopUp, Long> {

    /**
     * @param publicId the top-up's public id.
     * @return the top-up, or empty if none/soft-deleted.
     */
    Optional<TopUp> findByPublicId(UUID publicId);

    /**
     * @param idempotencyKey the initiation dedup key.
     * @return the existing top-up for this key, or empty (idempotent initiate, PRD §23.5).
     */
    Optional<TopUp> findByIdempotencyKey(String idempotencyKey);

    /**
     * @param provider    the settling rail.
     * @param providerRef the provider settlement reference.
     * @return the one matching top-up, or empty (the partial-unique reconciliation anchor).
     */
    Optional<TopUp> findByProviderAndProviderRef(MobileMoneyProvider provider, String providerRef);

    /**
     * Loads the top-up matching {@code (provider, providerRef)} with a row-level write lock for the duration
     * of the transaction.
     *
     * <p>WHY a pessimistic write lock on the reconciliation path: a provider may deliver the same callback
     * concurrently (two aggregator workers, a retry overlapping the original). Serialising on the row makes
     * the SUCCEEDED transition + wallet credit happen once even under concurrent delivery; the unique
     * constraints stop <i>replays</i>, the lock stops a <i>concurrent</i> double-credit (PRD §23.5
     * race-safe). The {@code @Version} optimistic lock on {@link TopUp} is the second line of defence.</p>
     *
     * @param provider    the settling rail.
     * @param providerRef the provider settlement reference.
     * @return the locked top-up, or empty if no row matches the reference yet.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopUp t where t.provider = :provider and t.providerRef = :providerRef")
    Optional<TopUp> findForUpdateByProviderRef(@Param("provider") MobileMoneyProvider provider,
                                               @Param("providerRef") String providerRef);

    /**
     * Claims a bounded batch of <b>stale, non-terminal</b> top-ups (INITIATED/PENDING created at or before
     * {@code olderThan}) under a row-level write lock, for the {@code @Scheduled} reconciliation job.
     *
     * <p>WHY a short, pessimistic-locked, bounded claim: the reconciliation job runs on a schedule on
     * possibly several instances; {@code FOR UPDATE} + {@code Pageable} (a small page) makes each tick claim a
     * disjoint, capped working set so two instances never re-check the same row, and the lock is held only for
     * the short job transaction (mirrors the outbox relay's claim discipline, ARCHITECTURE §10). Ordered by
     * {@code createdAt asc} so the oldest stragglers are resolved first. Rows the job confirms-or-expires are
     * driven to a terminal state, so they fall out of subsequent claims — the job is naturally idempotent.</p>
     *
     * @param olderThan the cutoff: only rows initiated at or before this instant are claimed (skip in-flight
     *                  attempts the citizen may still be approving on their handset).
     * @param pageable  the bounded claim window (page size caps the per-tick work).
     * @return the locked stale top-ups, oldest first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopUp t where t.status in "
            + "(com.taarifu.payments.domain.model.enums.TopUpStatus.INITIATED, "
            + " com.taarifu.payments.domain.model.enums.TopUpStatus.PENDING) "
            + "and t.createdAt <= :olderThan order by t.createdAt asc")
    List<TopUp> claimStaleNonTerminalForUpdate(@Param("olderThan") Instant olderThan, Pageable pageable);

    /**
     * Filtered, paged ADMIN search over top-ups by optional status / provider / created-at window.
     *
     * <p>WHY all filters are nullable-with-a-null-guard: one query serves every admin filter combination (DRY)
     * — a {@code null} parameter disables that predicate. Used by the admin payment query (list/search). The
     * result carries no MSISDN/secret (none is stored on the row); the DTO further trims it.</p>
     *
     * @param status   optional status filter ({@code null} = any).
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on {@code createdAt} ({@code null} = open-ended).
     * @param to       optional inclusive upper bound on {@code createdAt} ({@code null} = open-ended).
     * @param pageable the bounded page (size capped by {@code PageRequestFactory}).
     * @return the matching page of top-ups.
     */
    // NOTE: :from/:to are ALWAYS non-null here — PaymentQueryService coalesces an absent bound to a typed
    // sentinel. They must NOT use the `:p is null or ...` idiom: a NULL temporal bind in an IS NULL position
    // is untyped and PostgreSQL rejects the whole statement ("could not determine data type of parameter").
    // The nullable varchar status/provider filters keep the idiom safely.
    @Query("select t from TopUp t where "
            + "(:status   is null or t.status   = :status) and "
            + "(:provider is null or t.provider = :provider) and "
            + "t.createdAt >= :from and "
            + "t.createdAt <= :to")
    Page<TopUp> search(@Param("status") TopUpStatus status,
                       @Param("provider") MobileMoneyProvider provider,
                       @Param("from") Instant from,
                       @Param("to") Instant to,
                       Pageable pageable);

    /**
     * Sums {@code amount_minor} over the same filter window as {@link #search}, restricted to a given status
     * (e.g. {@code SUCCEEDED}) — the money total for the admin summary.
     *
     * <p>Returns {@code 0} (not {@code null}) when no row matches, via {@code coalesce}, so callers never
     * NPE on an empty window.</p>
     *
     * @param status   the status to total (e.g. {@code SUCCEEDED} for settled revenue).
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on {@code createdAt} ({@code null} = open-ended).
     * @param to       optional inclusive upper bound on {@code createdAt} ({@code null} = open-ended).
     * @return the summed minor-unit amount for matching rows (0 if none).
     */
    @Query("select coalesce(sum(t.amountMinor), 0) from TopUp t where "
            + "t.status = :status and "
            + "(:provider is null or t.provider = :provider) and "
            + "t.createdAt >= :from and "
            + "t.createdAt <= :to")
    long sumAmountMinorByStatus(@Param("status") TopUpStatus status,
                                @Param("provider") MobileMoneyProvider provider,
                                @Param("from") Instant from,
                                @Param("to") Instant to);

    /**
     * Counts rows of a given status within the same optional filter window — used by the admin summary
     * (e.g. how many SUCCEEDED, FAILED, PENDING in the window).
     *
     * @param status   the status to count.
     * @param provider optional rail filter ({@code null} = any).
     * @param from     optional inclusive lower bound on {@code createdAt} ({@code null} = open-ended).
     * @param to       optional inclusive upper bound on {@code createdAt} ({@code null} = open-ended).
     * @return the count of matching rows.
     */
    @Query("select count(t) from TopUp t where "
            + "t.status = :status and "
            + "(:provider is null or t.provider = :provider) and "
            + "t.createdAt >= :from and "
            + "t.createdAt <= :to")
    long countByStatus(@Param("status") TopUpStatus status,
                       @Param("provider") MobileMoneyProvider provider,
                       @Param("from") Instant from,
                       @Param("to") Instant to);
}
