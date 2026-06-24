package com.taarifu.identity;

import com.taarifu.AbstractPostgisIntegrationTest;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.identity.application.service.LoginService;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.identity.infrastructure.bootstrap.ProdAdminBootstrap;
import com.taarifu.identity.infrastructure.totp.TotpGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ProdAdminBootstrap} (the prod-safe, opt-in first-ROOT bootstrap) against a real Postgres
 * Testcontainer (threat-model E / TR-5 / G16).
 *
 * <p>WHY the bootstrap is <b>enabled via test properties here</b> (and the {@code dev} profile is NOT used):
 * unlike {@link DevAdminSeeder} (which is {@code @Profile("dev")}), this component is gated by the explicit
 * config flag {@code taarifu.bootstrap.admin.enabled=true} via {@code @ConditionalOnProperty} — so the test
 * turns the flag on and supplies a strong phone/password + a temp enrolment-file path, exactly as an
 * operator would for a single first-boot. The {@code test} profile uses {@code ddl-auto=create-drop} with
 * Flyway disabled, so the {@code role} catalogue (seeded by migration V102 in prod) is empty — the
 * {@link #seedRolesAndBootstrap()} setup therefore seeds the CITIZEN + ROOT catalogue rows and invokes the
 * bootstrap explicitly (the production component deliberately refuses to fabricate catalogue rows, unlike
 * the dev seeder). Running under the real security stack proves the bootstrapped ROOT is loginable
 * end-to-end.</p>
 *
 * <p>The invariants pinned here:</p>
 * <ul>
 *   <li>exactly one ACTIVE account at the configured phone, holding an <b>ACTIVE global-scope ROOT</b> grant
 *       plus the additive base CITIZEN grant (§6.4);</li>
 *   <li>the account is <b>loginable end-to-end</b> (password → MFA challenge → TOTP) using the secret from the
 *       0600 enrolment file — proving it is <b>not deadlocked</b> behind un-enrolled staff MFA (N-4);</li>
 *   <li>the bootstrap is <b>idempotent</b>: re-running {@code run} mints no second account / ROOT grant;</li>
 *   <li>the enrolment <b>file holds the otpauth secret but the bootstrap never logs it</b> (S-4) — asserted
 *       structurally via the file's presence/permissions.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "taarifu.bootstrap.admin.enabled=true",
        "taarifu.bootstrap.admin.phone=" + ProdAdminBootstrapIntegrationTest.BOOTSTRAP_PHONE,
        "taarifu.bootstrap.admin.password=" + ProdAdminBootstrapIntegrationTest.BOOTSTRAP_PASSWORD,
        "taarifu.bootstrap.admin.totp-enrollment-file="
                + ProdAdminBootstrapIntegrationTest.ENROLLMENT_FILE
})
@ActiveProfiles("test")
class ProdAdminBootstrapIntegrationTest extends AbstractPostgisIntegrationTest {

    /** A strong, clearly-test phone + password supplied via the bootstrap config (no source default exists). */
    static final String BOOTSTRAP_PHONE = "+255700000099";
    static final String BOOTSTRAP_PASSWORD = "Boot$trapRoot-2026";

    /**
     * Enrolment file the bootstrap writes the otpauth URI into; the test reads back the secret from it.
     * A compile-time-constant literal under {@code target/} (build-isolated, gitignored) so it can be used
     * in the {@code @SpringBootTest(properties=...)} annotation above.
     */
    static final String ENROLLMENT_FILE = "target/taarifu-test-root-totp-enrollment.txt";

    /** Pulls the Base32 secret out of an {@code otpauth://...?secret=XXXX&...} URI. */
    private static final Pattern SECRET = Pattern.compile("secret=([A-Z2-7]+)");

    @Autowired private ProdAdminBootstrap bootstrap;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RoleAssignmentRepository roleAssignmentRepository;
    @Autowired private LoginService loginService;
    @Autowired private ClockPort clock;
    @Autowired private TransactionTemplate txTemplate;

    /**
     * Seeds the role catalogue (empty under {@code create-drop}) and runs the bootstrap before each test.
     *
     * <p>WHY seed here rather than rely on the startup {@code ApplicationRunner}: the production component
     * refuses to fabricate the {@code role} catalogue (it requires migration V102), so under the
     * Flyway-disabled test schema the startup invocation would correctly no-op. Seeding CITIZEN + ROOT here
     * reproduces the migrated prod catalogue, then {@link ProdAdminBootstrap#run} performs the real
     * bootstrap. The bootstrap's own idempotency guard makes the repeated per-test invocation safe (the
     * second-test-onward run finds the existing ACTIVE ROOT and no-ops).</p>
     */
    @BeforeEach
    void seedRolesAndBootstrap() {
        txTemplate.executeWithoutResult(s -> {
            if (roleRepository.findByName(RoleName.CITIZEN).isEmpty()) {
                roleRepository.save(Role.create(RoleName.CITIZEN, "Registered citizen (base role)."));
            }
            if (roleRepository.findByName(RoleName.ROOT).isEmpty()) {
                roleRepository.save(Role.create(RoleName.ROOT, "Super-administrator."));
            }
        });
        bootstrap.run(new DefaultApplicationArguments());
    }

    @Test
    void bootstrapCreatesActiveGlobalScopeRoot() {
        User admin = userRepository.findByPhone(BOOTSTRAP_PHONE).orElseThrow();
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.getPasswordHash()).isNotBlank();
        assertThat(admin.getPasswordHash())
                .as("password is BCrypt-hashed, never stored in plaintext")
                .isNotEqualTo(BOOTSTRAP_PASSWORD);
        assertThat(admin.isMfaEnabled())
                .as("staff TOTP must be enrolled so first login is not deadlocked (N-4)")
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
            RoleAssignment root = grants.stream()
                    .filter(ra -> ra.getRole().getName() == RoleName.ROOT).findFirst().orElseThrow();
            assertThat(root.getAreaIds()).isEmpty();
            assertThat(root.getCategoryIds()).isEmpty();
            assertThat(root.getConstituency()).isNull();
        });
    }

    @Test
    void bootstrappedRootIsLoginableEndToEnd_passwordThenTotp() throws IOException {
        // Step 1: password login. Being a ROOT (staff) account, this returns an MFA challenge (N-4), not tokens.
        LoginService.LoginOutcome outcome =
                loginService.loginWithPassword(BOOTSTRAP_PHONE, BOOTSTRAP_PASSWORD);
        assertThat(outcome.mfaRequired())
                .as("a ROOT account must complete the staff second factor").isTrue();
        assertThat(outcome.tokens()).isNull();
        assertThat(outcome.mfaToken()).isNotBlank();

        // Step 2: the TOTP secret from the 0600 enrolment file completes the second factor → real token pair.
        String secret = readEnrolledSecret();
        String code = new TotpGenerator(30).codeAt(secret, clock.now().getEpochSecond());
        var pair = loginService.completeTotpLogin(outcome.mfaToken(), code);
        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
    }

    @Test
    void bootstrapIsIdempotent_noSecondRootOnRerun() {
        long usersBefore = userRepository.count();
        long rootGrantsBefore = countActiveRootGrants();

        // Re-run explicitly: it must detect the existing ACTIVE ROOT and do nothing.
        bootstrap.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(countActiveRootGrants()).isEqualTo(rootGrantsBefore);
        assertThat(userRepository.findByPhone(BOOTSTRAP_PHONE)).isPresent();
    }

    @Test
    void enrollmentFileWritten_andNotEmpty() throws IOException {
        Path file = Path.of(ENROLLMENT_FILE);
        assertThat(Files.exists(file)).as("the otpauth enrolment file is written for the operator").isTrue();
        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(body).contains("otpauth://totp/Taarifu:");
        assertThat(SECRET.matcher(body).find())
                .as("the file carries the Base32 TOTP secret (out-of-band, never the log — S-4)").isTrue();
    }

    /** Reads the Base32 TOTP secret back out of the otpauth URI in the enrolment file. */
    private String readEnrolledSecret() throws IOException {
        String body = Files.readString(Path.of(ENROLLMENT_FILE), StandardCharsets.UTF_8);
        Matcher m = SECRET.matcher(body);
        assertThat(m.find()).as("enrolment file must contain an otpauth secret").isTrue();
        return m.group(1);
    }

    /** @return the number of ACTIVE ROOT role grants in the DB (the privileged-account count). */
    private long countActiveRootGrants() {
        return txTemplate.execute(s -> roleAssignmentRepository.findAll().stream()
                .filter(ra -> ra.getRole() != null && ra.getRole().getName() == RoleName.ROOT)
                .filter(ra -> ra.getStatus() == RoleStatus.ACTIVE)
                .count());
    }
}
