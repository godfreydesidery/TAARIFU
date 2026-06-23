package com.taarifu.responders.domain.repository;

import com.taarifu.responders.domain.model.RoutingRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RoutingRule} (ARCHITECTURE.md §3.3, PRD §24.2).
 *
 * <p>Responsibility: admin management of the routing table and the lookup the reporting module uses
 * (via this module's public API) to resolve a category to a responder kind. Active rules for a
 * category are returned ordered by {@code priority} so the most specific/earliest rule wins
 * deterministically (§24.2 fallback ladder).</p>
 */
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {

    /**
     * @param publicId the rule's public id.
     * @return the rule, or empty if none/soft-deleted.
     */
    Optional<RoutingRule> findByPublicId(UUID publicId);

    /**
     * Lists all rules (admin view), paged.
     *
     * @param pageable paging/sorting.
     * @return a page of routing rules.
     */
    Page<RoutingRule> findAllBy(Pageable pageable);

    /**
     * Returns the active rules for a category, ordered by ascending priority (most-preferred first).
     *
     * <p>Used by routing resolution (in reporting, via this module's API) — a sub-category-specific rule
     * (lower/earlier priority by convention) precedes a whole-category rule, matching §24.2's
     * specific-over-general precedence.</p>
     *
     * @param categoryPublicId the reporting category id to route.
     * @return ordered active rules for that category (possibly empty → fall back to the §25.2 ladder).
     */
    @Query("""
            SELECT rr FROM RoutingRule rr
            WHERE rr.categoryPublicId = :categoryPublicId
              AND rr.active = true
            ORDER BY rr.priority ASC
            """)
    List<RoutingRule> findActiveByCategoryOrderByPriority(@Param("categoryPublicId") UUID categoryPublicId);
}
