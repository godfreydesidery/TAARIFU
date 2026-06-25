package com.taarifu.accountability.domain.repository;

import com.taarifu.accountability.domain.model.Promise;
import com.taarifu.accountability.domain.model.PromiseStatusEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link PromiseStatusEntry} — the append-only promise status timeline
 * (ARCHITECTURE.md &sect;3.3; PRD &sect;10 Epic M6, US-6.3).
 *
 * <p>Responsibility: powers the public "how did this promise move over time?" read. The timeline is
 * append-only, so there are intentionally no update/delete queries here. Soft-deleted rows are excluded by
 * the entity's {@code @SQLRestriction}.</p>
 */
public interface PromiseStatusEntryRepository extends JpaRepository<PromiseStatusEntry, Long> {

    /**
     * Lists a promise's status-timeline entries, paged. The caller orders by creation time (oldest-&gt;newest)
     * for the citizen-visible timeline.
     *
     * @param promise  the owning promise (same-module aggregate).
     * @param pageable paging/sorting.
     * @return the promise's timeline entries, paged.
     */
    Page<PromiseStatusEntry> findByPromise(Promise promise, Pageable pageable);
}
