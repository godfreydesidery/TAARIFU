package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link User} accounts (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: account lookups for authentication and the one-account-per-phone rule.
 * {@link #existsByPhone(String)} backs the signup guard (D11/D15); {@link #findByPhone(String)} backs
 * login. Public lookups use {@code publicId} (ADR-0006). Soft-deleted accounts are excluded by the
 * entity's {@code @SQLRestriction}.</p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * @param phone E.164 phone.
     * @return the account with that phone, or empty.
     */
    Optional<User> findByPhone(String phone);

    /**
     * @param publicId the account's public id.
     * @return the account, or empty.
     */
    Optional<User> findByPublicId(UUID publicId);

    /**
     * @param phone E.164 phone.
     * @return {@code true} if an account already uses this phone (one account per phone — D11/D15).
     */
    boolean existsByPhone(String phone);

    /**
     * @param email login-alias email (not unique; first match wins).
     * @return the account with that email, or empty (used by email-password login).
     */
    Optional<User> findByEmail(String email);

    /**
     * The back-office user-management list query (M14, US-14.1) backing
     * {@code UserAdminQueryApi.listUsers}: a filtered, paginated page of accounts joined to their profile
     * (for the name filter) and, when a role filter is supplied, to an <b>active</b> role grant of that role.
     *
     * <p>WHY one JPQL query with all-nullable params (the {@code :x IS NULL OR …} idiom): the admin table
     * filters on any combination of name / phone-suffix / tier / role / status, each optional. A single
     * query keeps the contract explicit and lets Spring Data derive the matching {@code count} query for
     * correct pagination (no hand-rolled {@code Specification}, matching house style — no
     * {@code JpaSpecificationExecutor} is used elsewhere). {@code DISTINCT} guards against row fan-out when
     * an account holds several grants of the filtered role.</p>
     *
     * <p><b>Privacy:</b> the {@code phoneSuffix} match uses {@code LIKE %suffix} against the stored phone so
     * an operator can search by the trailing digits a citizen reads back; the projection that consumes the
     * result still <b>masks</b> the phone (PRD §18). The name match is case-insensitive over the profile's
     * first/last name. All enum filters arrive parsed (a stale/unknown value is mapped to {@code null} by the
     * caller so it does not constrain — never throwing).</p>
     *
     * @param name        case-insensitive name fragment, or {@code null} for no name filter.
     * @param phoneSuffix trailing phone digits, or {@code null} for no phone filter.
     * @param tier        trust tier to filter to, or {@code null} for any.
     * @param status      account status to filter to, or {@code null} for any.
     * @param role        role whose <b>active</b> grant the account must hold, or {@code null} for any role.
     * @param activeRoleStatus the grant status that counts as "holding" the role (pass
     *                         {@link RoleStatus#ACTIVE}); only consulted when {@code role} is non-null.
     * @param pageable    the (already-capped) page request.
     * @return a page of matching accounts (never {@code null}).
     */
    @Query("""
            SELECT DISTINCT u FROM User u
              LEFT JOIN Profile p ON p.user = u
            WHERE (CAST(:name AS string) IS NULL
                   OR LOWER(COALESCE(p.firstName, '')) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%'))
                   OR LOWER(COALESCE(p.lastName, ''))  LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
              AND (CAST(:phoneSuffix AS string) IS NULL
                   OR u.phone LIKE CONCAT('%', CAST(:phoneSuffix AS string)))
              AND (:tier IS NULL OR u.trustTier = :tier)
              AND (:status IS NULL OR u.status = :status)
              AND (:role IS NULL OR EXISTS (
                     SELECT ra FROM RoleAssignment ra
                      WHERE ra.user = u AND ra.role.name = :role AND ra.status = :activeRoleStatus))
            """)
    Page<User> adminSearch(@Param("name") String name,
                           @Param("phoneSuffix") String phoneSuffix,
                           @Param("tier") TrustTier tier,
                           @Param("status") UserStatus status,
                           @Param("role") RoleName role,
                           @Param("activeRoleStatus") RoleStatus activeRoleStatus,
                           Pageable pageable);
}
