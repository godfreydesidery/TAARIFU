package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.identity.application.service.LoginService;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.identity.infrastructure.bootstrap.DevAdminSeeder;
import com.taarifu.identity.infrastructure.totp.TotpGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link DevAdminSeeder} (dev-only bootstrap admin) against a real Postgres Testcontainer.
 *
 * <p>WHY both the {@code test} and {@code dev} profiles are active: the seeder is {@code @Profile("dev")} so
 * it only loads when {@code dev} is active, while {@code test} supplies the dummy JWT/crypto secrets and the
 * logging SMS/crypto stubs the context needs to boot (and uses {@code ddl-auto=create-drop}, so the
 * {@code role} table starts empty — exactly the fresh-dev situation the seeder must cope with). Running both
 * proves the seeder works end-to-end under the same security stack the admin console hits.</p>
 *
 * <p>WHY the role-navigating assertions run inside a {@link TransactionTemplate}: {@link RoleAssignment}'s
 * {@code role} is a LAZY {@code @ManyToOne}; reading {@code getRole().getName()} outside a session would
 * raise {@code LazyInitializationException}. The transaction keeps the proxy initialisable — it is a test
 * concern only, not a production access pattern.</p>
 *
 * <p>The invariants pinned here:</p>
 * <ul>
 *   <li>the seeder created exactly one account at the fixed dev phone, ACTIVE, holding an <b>ACTIVE ROOT</b>
 *       grant (and the additive base CITIZEN grant);</li>
 *   <li>that account is <b>password-loginable</b> via the real {@link LoginService} — and, being a staff
 *       (ROOT) account, the login returns an MFA challenge, not a token pair (proves the MFA gate is wired);</li>
 *   <li>the pre-enrolled fixed TOTP secret completes the second factor, yielding a real token pair (proves
 *       the account is loginable <b>end-to-end</b>, not deadlocked behind un-enrolled MFA);</li>
 *   <li>the seeder is <b>idempotent</b>: invoking {@code run} again creates no second account and no second
 *       ROOT grant.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"test", "dev"})
class DevAdminSeederIntegrationTest extends AbstractPostgisIntegrationTest {

    /** The fixed dev identifier the seeder uses (kept in sync with the seeder's package-private constant). */
    private static final String DEV_PHONE = "+255700000001";
    private static final String DEV_PASSWORD = "Admin@12345";
    private static final String DEV_TOTP_SECRET = "JBSWY3DPEHPK3PXP";

    @Autowired private DevAdminSeeder seeder;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleAssignmentRepository roleAssignmentRepository;
    @Autowired private LoginService loginService;
    @Autowired private ClockPort clock;
    @Autowired private TransactionTemplate txTemplate;

    @Test
    void seederCreatesActiveRootAdmin() {
        User admin = userRepository.findByPhone(DEV_PHONE).orElseThrow();
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.getPasswordHash()).isNotBlank();
        assertThat(admin.isMfaEnabled()).as("staff TOTP must be pre-enrolled so login is not deadlocked")
                .isTrue();

        txTemplate.executeWithoutResult(s -> {
            List<RoleAssignment> grants = roleAssignmentRepository.findByUser(admin);
            assertThat(grants)
                    .as("the bootstrap account holds an ACTIVE ROOT grant")
                    .anyMatch(ra -> ra.getRole().getName() == RoleName.ROOT
                            && ra.getStatus() == RoleStatus.ACTIVE);
            assertThat(grants)
                    .as("roles are additive — the base CITIZEN grant is kept (§6.4)")
                    .anyMatch(ra -> ra.getRole().getName() == RoleName.CITIZEN);
            // Global scope: the ROOT grant must carry no area/category/constituency restriction.
            RoleAssignment root = grants.stream()
                    .filter(ra -> ra.getRole().getName() == RoleName.ROOT).findFirst().orElseThrow();
            assertThat(root.getAreaIds()).isEmpty();
            assertThat(root.getCategoryIds()).isEmpty();
            assertThat(root.getConstituency()).isNull();
        });
    }

    @Test
    void adminIsLoginableEndToEnd_passwordThenTotp() {
        // Step 1: password login. Being a ROOT (staff) account, this returns an MFA challenge (N-4), not tokens.
        LoginService.LoginOutcome outcome = loginService.loginWithPassword(DEV_PHONE, DEV_PASSWORD);
        assertThat(outcome.mfaRequired()).as("a ROOT account must complete the staff second factor").isTrue();
        assertThat(outcome.tokens()).isNull();
        assertThat(outcome.mfaToken()).isNotBlank();

        // Step 2: the fixed dev TOTP secret completes the second factor and yields the real token pair.
        String code = new TotpGenerator(30).codeAt(DEV_TOTP_SECRET, clock.now().getEpochSecond());
        var pair = loginService.completeTotpLogin(outcome.mfaToken(), code);
        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
    }

    @Test
    void seederIsIdempotent() {
        long usersBefore = userRepository.count();
        long rootGrantsBefore = countActiveRootGrants();

        // Re-run the seeder explicitly: it must detect the existing bootstrap admin and do nothing.
        seeder.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(countActiveRootGrants()).isEqualTo(rootGrantsBefore);
        // And there is still exactly one account on the dev phone (no duplicate).
        assertThat(userRepository.findByPhone(DEV_PHONE)).isPresent();
    }

    /** @return the number of ACTIVE ROOT role grants in the DB (the privileged-account count). */
    private long countActiveRootGrants() {
        return txTemplate.execute(s -> roleAssignmentRepository.findAll().stream()
                .filter(ra -> ra.getRole() != null && ra.getRole().getName() == RoleName.ROOT)
                .filter(ra -> ra.getStatus() == RoleStatus.ACTIVE)
                .count());
    }
}
