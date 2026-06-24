package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.identity.api.UserAdminApi;
import com.taarifu.identity.api.UserAdminQueryApi;
import com.taarifu.identity.api.dto.GrantRoleCommand;
import com.taarifu.identity.api.dto.UserAdminDetail;
import com.taarifu.identity.api.dto.UserAdminFilter;
import com.taarifu.identity.api.dto.UserAdminPage;
import com.taarifu.identity.api.dto.UserAdminSummary;
import com.taarifu.identity.api.dto.UserRoleGrant;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers tests for the identity-owned {@link com.taarifu.identity.application.service.UserAdminService}
 * — the live implementation of the back-office user-management ports (M14, US-14.1; PRD §7.2 RBAC, D15
 * additive roles).
 *
 * <p>Responsibility: prove the integrity invariants the admin user-management API depends on, against a real
 * Postgres (the role catalogue is seeded by V102, the audit constraint widened by V103):</p>
 * <ul>
 *   <li><b>Additive + idempotent grant (D15):</b> granting a new role keeps existing roles and creates one
 *       grant; re-granting an already-held active role is a no-op success returning the same assignment id
 *       (no duplicate row).</li>
 *   <li><b>Revoke is an end-date, never a delete (§6.4/§18):</b> the grant becomes {@code FORMER} and stays
 *       in the table; and the account's <b>last active CITIZEN</b> base role may not be revoked
 *       ({@code BAD_REQUEST}).</li>
 *   <li><b>Ownership guard:</b> revoking an assignment that belongs to another account is {@code NOT_FOUND}.</li>
 *   <li><b>Suspend/reinstate</b> flip {@code UserStatus} and audit; <b>list/detail</b> mask the phone and
 *       never leak the {@code idNo}.</li>
 * </ul>
 *
 * <p>Each test exercises the published ports ({@link UserAdminQueryApi}/{@link UserAdminApi}) exactly as the
 * admin module calls them, so the cross-module contract is what is verified.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class UserAdminServiceIntegrationTest extends AbstractPostgisIntegrationTest {

    @Autowired
    private UserAdminQueryApi userQuery;
    @Autowired
    private UserAdminApi userAdmin;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProfileRepository profileRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    private final UUID actingAdmin = UUID.randomUUID();

    /**
     * Seeds the role catalogue and cleans the identity tables between methods. WHY here (not from V102): the
     * {@code test} profile runs with Flyway disabled + {@code ddl-auto=create-drop} (schema from entities,
     * not migrations), so the V102 catalogue is not present and rows persist across methods under create-drop
     * — exactly as {@code AuthFlowIntegrationTest} seeds CITIZEN itself. We seed the three roles these tests
     * grant.
     *
     * <p>WHY a {@link TransactionTemplate} rather than {@code @Transactional} on this {@code @BeforeEach}:
     * the annotation is not woven on a JUnit lifecycle callback (no AOP proxy; the test transaction listener
     * only manages the {@code @Test} method's transaction), so the native {@code executeUpdate} calls would
     * raise {@code TransactionRequiredException}. The programmatic transaction binds a manager and commits
     * the catalogue seed before each test runs in its own (rolled-back) {@code @Transactional} method.</p>
     */
    @BeforeEach
    void seedRolesAndClean() {
        txTemplate.executeWithoutResult(s -> {
            // Delete child rows before app_user (FK order) so this cleanup is robust to rows another test
            // class left behind under the shared create-drop schema (e.g. refresh_token/otp_challenge).
            em.createNativeQuery("DELETE FROM audit_event").executeUpdate();
            em.createNativeQuery("DELETE FROM refresh_token").executeUpdate();
            em.createNativeQuery("DELETE FROM otp_challenge").executeUpdate();
            em.createNativeQuery("DELETE FROM role_assignment").executeUpdate();
            em.createNativeQuery("DELETE FROM profile_location").executeUpdate();
            em.createNativeQuery("DELETE FROM profile").executeUpdate();
            em.createNativeQuery("DELETE FROM app_user").executeUpdate();
            em.createNativeQuery("DELETE FROM role").executeUpdate();
            seedRole("CITIZEN", "Registered citizen");
            seedRole("MODERATOR", "Content/safety moderator");
            seedRole("RESPONDER_AGENT", "Responder agent");
        });
    }

    private void seedRole(String name, String description) {
        em.createNativeQuery("""
                INSERT INTO role (public_id, version, created_at, deleted, name, description)
                VALUES (:pid, 0, now(), false, :name, :desc)
                """)
                .setParameter("pid", UUID.randomUUID())
                .setParameter("name", name)
                .setParameter("desc", description)
                .executeUpdate();
    }

    /** Creates an ACTIVE citizen account with a named profile and the base CITIZEN grant; returns its publicId. */
    @Transactional
    UUID newCitizen(String phone, String first, String last) {
        User user = User.createPending(phone);
        user.activate();
        user = userRepository.save(user);

        Profile profile = Profile.createPersonForSignup(user);
        profile.updateDetails(first, last, null, null, null);
        profileRepository.save(profile);

        Role citizen = roleRepository.findByName(RoleName.CITIZEN).orElseThrow();
        roleAssignmentRepository.save(RoleAssignment.grant(user, citizen, RoleStatus.ACTIVE));
        return user.getPublicId();
    }

    @Test
    @Transactional
    void grantRole_isAdditive_keepsCitizen_andIsIdempotent() {
        UUID citizen = newCitizen("+255700100001", "Asha", "Mwananchi");
        GrantRoleCommand cmd = new GrantRoleCommand("MODERATOR", null, null, null, null, null);

        UUID assignmentId = userAdmin.grantRole(actingAdmin, citizen, cmd);
        assertThat(assignmentId).isNotNull();

        // Additive: the account now holds CITIZEN + MODERATOR (both active).
        UserAdminDetail detail = userQuery.getUser(citizen);
        assertThat(detail.roles()).extracting(UserRoleGrant::roleName)
                .contains("CITIZEN", "MODERATOR");

        // Idempotent: re-granting the same active role returns the SAME assignment id, no duplicate row.
        UUID again = userAdmin.grantRole(actingAdmin, citizen, cmd);
        assertThat(again).isEqualTo(assignmentId);

        long moderatorGrants = userQuery.getUser(citizen).roles().stream()
                .filter(g -> g.roleName().equals("MODERATOR") && g.status().equals("ACTIVE"))
                .count();
        assertThat(moderatorGrants).isEqualTo(1L);
    }

    @Test
    @Transactional
    void grantRole_unknownRole_isBadRequest() {
        UUID citizen = newCitizen("+255700100002", "Juma", "Hassan");
        assertThatThrownBy(() -> userAdmin.grantRole(actingAdmin, citizen,
                new GrantRoleCommand("NOT_A_ROLE", null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @Transactional
    void revokeRole_endDatesGrant_neverDeletes() {
        UUID citizen = newCitizen("+255700100003", "Neema", "Kileo");
        UUID assignmentId = userAdmin.grantRole(actingAdmin, citizen,
                new GrantRoleCommand("RESPONDER_AGENT", null, null, null, null, null));

        userAdmin.revokeRole(actingAdmin, citizen, assignmentId);

        // The row still exists (history kept) but is FORMER and end-dated — no hard delete (§6.4/§18).
        RoleAssignment ra = roleAssignmentRepository.findByPublicId(assignmentId).orElseThrow();
        assertThat(ra.getStatus()).isEqualTo(RoleStatus.FORMER);
        assertThat(ra.getEffectiveTo()).isNotNull();
    }

    @Test
    @Transactional
    void revokeRole_lastCitizenBaseRole_isBadRequest() {
        UUID citizen = newCitizen("+255700100004", "Baraka", "Mushi");
        UserAdminDetail detail = userQuery.getUser(citizen);
        UUID citizenAssignment = detail.roles().stream()
                .filter(g -> g.roleName().equals("CITIZEN"))
                .map(UserRoleGrant::assignmentId)
                .findFirst().orElseThrow();

        // The base CITIZEN role may not be revoked — every account keeps its civic base role.
        assertThatThrownBy(() -> userAdmin.revokeRole(actingAdmin, citizen, citizenAssignment))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @Transactional
    void revokeRole_assignmentOfAnotherAccount_isNotFound() {
        UUID a = newCitizen("+255700100005", "A", "One");
        UUID b = newCitizen("+255700100006", "B", "Two");
        UUID bModerator = userAdmin.grantRole(actingAdmin, b,
                new GrantRoleCommand("MODERATOR", null, null, null, null, null));

        // Revoking B's grant via A's path id must be NOT_FOUND (ownership guard).
        assertThatThrownBy(() -> userAdmin.revokeRole(actingAdmin, a, bModerator))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @Transactional
    void suspendThenReinstate_flipsStatus() {
        UUID citizen = newCitizen("+255700100007", "Said", "Ally");

        userAdmin.suspendUser(actingAdmin, citizen, "POLICY_VIOLATION");
        assertThat(userRepository.findByPublicId(citizen).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SUSPENDED);

        userAdmin.reinstateUser(actingAdmin, citizen);
        assertThat(userRepository.findByPublicId(citizen).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @Transactional
    void getUser_unknown_isNotFound() {
        assertThatThrownBy(() -> userQuery.getUser(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @Transactional
    void listAndDetail_maskThePhone_andNeverLeakRawNumber() {
        String rawPhone = "+255712345678";
        UUID citizen = newCitizen(rawPhone, "Lulu", "Mwinyi");

        UserAdminDetail detail = userQuery.getUser(citizen);
        assertThat(detail.maskedPhone()).doesNotContain("1234"); // middle digits masked
        assertThat(detail.maskedPhone()).startsWith("+2557").endsWith("5678").contains("****");
        assertThat(detail.displayName()).isEqualTo("Lulu Mwinyi");

        UserAdminPage<UserAdminSummary> page =
                userQuery.listUsers(new UserAdminFilter(null, "5678", null, null, null), 0, 20);
        assertThat(page.content()).anySatisfy(s -> {
            assertThat(s.maskedPhone()).contains("****");
            assertThat(s.maskedPhone()).isNotEqualTo(rawPhone);
        });
    }

    @Test
    @Transactional
    void listUsers_filtersByRole_andStaleFilterReturnsEmpty() {
        UUID citizen = newCitizen("+255700100008", "Hawa", "Juma");
        userAdmin.grantRole(actingAdmin, citizen,
                new GrantRoleCommand("MODERATOR", null, null, null, null, null));
        em.flush();

        // Filtering by an ACTIVE MODERATOR grant finds the account.
        UserAdminPage<UserAdminSummary> moderators =
                userQuery.listUsers(new UserAdminFilter(null, null, null, "MODERATOR", null), 0, 20);
        assertThat(moderators.content()).extracting(UserAdminSummary::publicId).contains(citizen);

        // A stale/unknown role filter is treated as "no match", never a 500.
        UserAdminPage<UserAdminSummary> garbage =
                userQuery.listUsers(new UserAdminFilter(null, null, null, "NOT_A_ROLE", null), 0, 20);
        assertThat(garbage.content()).isEmpty();
    }

    @Test
    @Transactional
    void grantRole_withScopeAndEffectiveWindow_isPersistedOnTheGrant() {
        UUID citizen = newCitizen("+255700100009", "Emmanuel", "Shirima");
        UUID area = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        var from = java.time.Instant.parse("2026-07-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-12-31T00:00:00Z");

        UUID assignmentId = userAdmin.grantRole(actingAdmin, citizen,
                new GrantRoleCommand("RESPONDER_AGENT", java.util.Set.of(area), java.util.Set.of(category),
                        null, from, to));

        UserRoleGrant grant = userQuery.getUser(citizen).roles().stream()
                .filter(g -> g.assignmentId().equals(assignmentId))
                .findFirst().orElseThrow();
        assertThat(grant.areaIds()).containsExactly(area);
        assertThat(grant.categoryIds()).containsExactly(category);
        assertThat(grant.effectiveFrom()).isEqualTo(from);
        assertThat(grant.effectiveTo()).isEqualTo(to);
    }
}
