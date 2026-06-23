package com.taarifu.reporting.domain.repository;

import com.taarifu.reporting.domain.model.CaseEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository for {@link CaseEvent} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: reads a report's timeline. Two variants exist by design: the full timeline (for the
 * reporter/responder who may see internal notes) and the public-only timeline (US-3.4 "public vs internal
 * notes"). The {@code report.publicId} join keeps the boundary at the public id, never the internal FK.</p>
 */
public interface CaseEventRepository extends JpaRepository<CaseEvent, Long> {

    /**
     * Returns the full timeline for a report (public + internal events) — for the reporter/owner view.
     *
     * @param reportPublicId the report's public id.
     * @param pageable       bounded paging/sorting.
     * @return a page of the report's events, newest first by id when sorted accordingly.
     */
    Page<CaseEvent> findByReport_PublicId(UUID reportPublicId, Pageable pageable);

    /**
     * Returns only the <b>public</b> timeline events for a report — for citizen-facing/public views; never
     * leaks internal responder notes (US-3.4).
     *
     * @param reportPublicId the report's public id.
     * @param pageable       bounded paging/sorting.
     * @return a page of the report's public events.
     */
    Page<CaseEvent> findByReport_PublicIdAndPublicEventTrue(UUID reportPublicId, Pageable pageable);
}
