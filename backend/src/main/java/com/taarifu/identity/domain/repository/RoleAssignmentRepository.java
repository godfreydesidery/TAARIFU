package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RoleAssignment} (ARCHITECTURE.md §3.3, §6.2).
 *
 * <p>Responsibility: loads a user's role grants (with their scope) for authorization. The scope-checker
 * (auth increment) reads these to enforce area/category/constituency limits. Public lookup by
 * {@code publicId} (ADR-0006).</p>
 */
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {

    /**
     * @param user the account.
     * @return all role grants held by the account (additive roles — §6.4).
     */
    List<RoleAssignment> findByUser(User user);

    /**
     * @param userPublicId the account's public id.
     * @return all role grants held by that account (additive roles — §6.4).
     */
    List<RoleAssignment> findByUser_PublicId(UUID userPublicId);

    /**
     * @param userPublicId the account's public id.
     * @param status       the grant status to filter by (typically {@link RoleStatus#ACTIVE}).
     * @return matching grants — the live scope source for the {@code ScopeGuard} (MF-3).
     */
    List<RoleAssignment> findByUser_PublicIdAndStatus(UUID userPublicId, RoleStatus status);

    /**
     * Returns a user's role grants that are <b>both</b> {@link RoleStatus#ACTIVE} <b>and</b> within their
     * effective window at {@code now} — the single, DRY source of "grants that authorize right now",
     * shared by the {@code ScopeGuard} (scope/role-X-scope checks) and {@code TokenService} (the
     * access-token role claim).
     *
     * <p><b>N-2 (P2) — honour the effective window.</b> WHY this exists and is shared: a grant carries
     * {@code effectiveFrom}/{@code effectiveTo} precisely so it can be future-dated or time-boxed (e.g. an
     * election-period responder/representative mandate). Filtering on {@code status = ACTIVE} alone let a
     * grant that is ACTIVE but past its {@code effectiveTo} (lapsed) or before its {@code effectiveFrom}
     * (not yet effective) still pass scope checks and still emit a role authority — an
     * integrity/neutrality risk (a lapsed representative/responder still acting). Both authorization
     * call sites must apply the identical predicate, so it lives in <b>one</b> query rather than being
     * duplicated (DRY, CLAUDE.md §3). A {@code null} {@code effectiveFrom} means "effective on creation"
     * (per {@link RoleAssignment}), and a {@code null} {@code effectiveTo} means "open-ended"; both are
     * handled here. The bound is {@code effectiveTo > now} (exclusive) so a grant is no longer effective
     * at the instant it ends.</p>
     *
     * @param userPublicId the account's public id.
     * @param now          the instant to evaluate the window against (from the injected {@code ClockPort}
     *                     so it is testable — never {@link Instant#now()} inline).
     * @return the grants that are active and effective at {@code now}; empty if none.
     */
    @Query("""
            SELECT ra FROM RoleAssignment ra
            WHERE ra.user.publicId = :userPublicId
              AND ra.status = com.taarifu.identity.domain.model.enums.RoleStatus.ACTIVE
              AND (ra.effectiveFrom IS NULL OR ra.effectiveFrom <= :now)
              AND (ra.effectiveTo IS NULL OR ra.effectiveTo > :now)
            """)
    List<RoleAssignment> findActiveEffectiveByUser(@Param("userPublicId") UUID userPublicId,
                                                   @Param("now") Instant now);

    /**
     * @param publicId the assignment's public id.
     * @return the assignment, or empty.
     */
    Optional<RoleAssignment> findByPublicId(UUID publicId);
}
