package com.taarifu.identity.infrastructure.bootstrap;

import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.RoleRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev-only bootstrap of a single, loginable staff <b>admin</b> account so the Angular admin console has a
 * credential in local dev/demo (there is no user seed migration — the platform ships with zero accounts).
 *
 * <p><b>WHY this exists and why it is strictly dev-only.</b> Production must never ship with a known,
 * hard-coded administrator — that is a standing backdoor (CLAUDE.md §12, PRD §18). This component is
 * therefore annotated {@code @Profile("dev")}: Spring never instantiates it under any other profile, so a
 * staging/production boot (which does not activate {@code dev}) is completely unaffected — no bean, no
 * runner, no account. The credentials below are convenience scaffolding for a developer's laptop and a
 * demo box ONLY; a real environment provisions its first admin through the (audited) admin grant path.</p>
 *
 * <p><b>What it creates</b> (idempotently, see below), reusing the existing identity factories/services so
 * the account is byte-for-byte equivalent to one minted by the normal flows:</p>
 * <ul>
 *   <li>an {@link com.taarifu.identity.domain.model.enums.UserStatus#ACTIVE ACTIVE} {@link User} at the
 *       fixed dev phone {@value #DEV_ADMIN_PHONE}, with a <b>BCrypt</b> password hash produced by the shared
 *       {@link PasswordEncoder} (never plaintext — ADR-0007);</li>
 *   <li>staff <b>TOTP pre-enrolled</b> ({@code mfaEnabled=true} + a fixed, well-known dev secret) — see the
 *       deadlock note below;</li>
 *   <li>a phone-verified {@link Profile} (the 1:1 invariant every identity flow assumes);</li>
 *   <li>the base {@link RoleName#CITIZEN} grant (roles are additive on one account — §6.4/D12) plus a
 *       <b>global-scope</b> (no area/category/constituency restriction) {@link RoleName#ROOT}
 *       {@link RoleAssignment} in status {@link RoleStatus#ACTIVE}, effective now.</li>
 * </ul>
 *
 * <p><b>WHY TOTP must be pre-enrolled (the deadlock this avoids).</b> Any account holding a staff role
 * (MODERATOR/ADMIN/ROOT) is forced through the second factor at login by {@code MfaLoginGate}: a staff
 * account with no enrolled TOTP cannot complete {@code POST /auth/login/password} at all (it raises
 * {@code MFA_REQUIRED}). A bootstrap admin that could not log in would be useless, so this seeder enrols a
 * fixed dev secret directly — promoting it through {@link User#enableMfa()} exactly as
 * {@code TotpService.activate} does — and the {@value #DEV_TOTP_SECRET} secret is the RFC 4648/6238 test
 * vector, so codes are reproducible in any authenticator. The full login recipe (password → MFA challenge
 * → TOTP) is printed at WARN on startup.</p>
 *
 * <p><b>Idempotency.</b> The runner is a no-op (logged at INFO) if a bootstrap-equivalent account already
 * exists — either an account already uses the dev phone, OR any account already holds an
 * {@link RoleStatus#ACTIVE} {@link RoleName#ROOT}/{@link RoleName#ADMIN} grant. This makes repeated boots,
 * and a boot against a DB that already has a real admin, safe: it never creates a second privileged
 * account and never overwrites an operator's deliberate admin.</p>
 *
 * <p><b>WHY it also materialises the role catalogue rows.</b> No migration seeds the {@code role} table, so
 * in a fresh dev DB it is empty; the seeder creates the {@link RoleName#CITIZEN} and {@link RoleName#ROOT}
 * catalogue rows if absent (idempotently) before granting — staying inside the identity module, which owns
 * that table. This does not affect non-dev environments (the bean does not load there).</p>
 *
 * <p><b>Security/PDPA notes (S-4).</b> The bootstrap admin carries <b>no national/voter ID</b> (no PII), so
 * nothing field-encrypted or blind-indexed is written for it. The only secret logged is the dev password —
 * deliberately, and only in {@code dev}, behind an explicit "DEV ONLY — never in production" banner. No
 * other secret (the TOTP secret aside, which is itself a public test vector) is sensitive here.</p>
 */
@Component
@org.springframework.context.annotation.Profile("dev")
public class DevAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminSeeder.class);

    /** The fixed dev identifier (E.164). Login uses this as the {@code accountKey} (phone). */
    static final String DEV_ADMIN_PHONE = "+255700000001";

    /** Display name for the bootstrap profile (no PII). */
    private static final String DEV_ADMIN_FIRST_NAME = "Dev";
    private static final String DEV_ADMIN_LAST_NAME = "Admin";

    /**
     * The fallback dev password, used ONLY when {@code TAARIFU_DEV_ADMIN_PASSWORD} is unset. It is a clearly
     * non-production value and is only ever reachable under the {@code dev} profile (this bean never loads
     * otherwise). It is BCrypt-hashed before storage — never persisted in plaintext.
     */
    static final String DEFAULT_DEV_ADMIN_PASSWORD = "Admin@12345";

    /**
     * The fixed, well-known dev TOTP secret (the canonical RFC 4648 Base32 test vector). Pre-enrolled so the
     * staff second factor is satisfiable with reproducible codes in any authenticator. Acceptable to hard-code
     * because (a) it is a published test vector, not a secret with value, and (b) this bean is dev-only.
     */
    static final String DEV_TOTP_SECRET = "JBSWY3DPEHPK3PXP";

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;

    /** The dev admin password from the environment, or the clearly-dev default when unset. Never logged except in the dev banner. */
    private final String configuredPassword;

    /**
     * @param userRepository           account persistence + the one-account-per-phone idempotency check.
     * @param profileRepository        profile persistence (the 1:1 invariant).
     * @param roleRepository           role-catalogue lookup/creation (CITIZEN + ROOT rows).
     * @param roleAssignmentRepository the additive CITIZEN grant + the global-scope ROOT grant; also the
     *                                 idempotency check for an existing ROOT/ADMIN holder.
     * @param passwordEncoder          the shared BCrypt encoder (never stores plaintext — ADR-0007).
     * @param configuredPassword       {@code TAARIFU_DEV_ADMIN_PASSWORD} if set, else
     *                                 {@value #DEFAULT_DEV_ADMIN_PASSWORD} (dev-only fallback).
     */
    public DevAdminSeeder(UserRepository userRepository,
                          ProfileRepository profileRepository,
                          RoleRepository roleRepository,
                          RoleAssignmentRepository roleAssignmentRepository,
                          PasswordEncoder passwordEncoder,
                          @Value("${TAARIFU_DEV_ADMIN_PASSWORD:" + DEFAULT_DEV_ADMIN_PASSWORD + "}")
                          String configuredPassword) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuredPassword = configuredPassword;
    }

    /**
     * Seeds the dev admin once at startup, idempotently.
     *
     * @param args the application arguments (unused).
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapAdminAlreadyExists()) {
            log.info("DevAdminSeeder: a bootstrap-equivalent admin already exists (dev phone in use or an "
                    + "ACTIVE ROOT/ADMIN grant present) — skipping seed.");
            return;
        }

        Role citizenRole = ensureRole(RoleName.CITIZEN, "Registered citizen (base role).");
        Role rootRole = ensureRole(RoleName.ROOT, "Super-administrator (dev bootstrap).");

        User admin = createActiveStaffAccount();
        Profile profile = Profile.createPersonForSignup(admin);
        // A complete name + phone-verified profile so any post-login flow that resolves the profile is happy.
        profile.updateDetails(DEV_ADMIN_FIRST_NAME, DEV_ADMIN_LAST_NAME, null, null, null);
        profileRepository.save(profile);

        // Additive roles on one account (§6.4/D12): keep the base CITIZEN grant alongside the staff role.
        roleAssignmentRepository.save(RoleAssignment.grant(admin, citizenRole, RoleStatus.ACTIVE));
        // Global scope = no area/category/constituency restriction (unrestricted ROOT), effective now.
        roleAssignmentRepository.save(RoleAssignment.grant(admin, rootRole, RoleStatus.ACTIVE));

        // Cache a sensible tier hint (the live resolver still governs gating — MF-2). The bootstrap admin has
        // a complete, phone-verified profile but no ID verification, so T2 is the correct cached hint.
        admin.setTrustTier(TrustTier.T2);

        logDevCredentialsBanner();
    }

    /**
     * @return {@code true} if a bootstrap-equivalent admin already exists — the dev phone is taken, or any
     *         account already holds an ACTIVE ROOT/ADMIN grant (so we never mint a second privileged account).
     */
    private boolean bootstrapAdminAlreadyExists() {
        if (userRepository.existsByPhone(DEV_ADMIN_PHONE)) {
            return true;
        }
        return hasActiveHolder(RoleName.ROOT) || hasActiveHolder(RoleName.ADMIN);
    }

    /** @return whether any account holds an ACTIVE grant of the given role (catalogue row may not exist yet). */
    private boolean hasActiveHolder(RoleName roleName) {
        return roleRepository.findByName(roleName)
                .map(role -> roleAssignmentRepository.findAll().stream()
                        .anyMatch(ra -> ra.getRole() != null
                                && ra.getRole().getName() == roleName
                                && ra.getStatus() == RoleStatus.ACTIVE))
                .orElse(false);
    }

    /** Looks up a role-catalogue row by name, creating it (dev only) if no migration seeded it. */
    private Role ensureRole(RoleName name, String description) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(Role.create(name, description)));
    }

    /**
     * Builds the ACTIVE staff account: BCrypt password, and TOTP pre-enrolled with the fixed dev secret via
     * the same pending→active promotion {@code TotpService.activate} performs (so the active secret + the
     * {@code mfaEnabled} flag are set exactly as a real enrolment leaves them, satisfying the staff MFA gate).
     */
    private User createActiveStaffAccount() {
        User admin = User.createPending(DEV_ADMIN_PHONE);
        admin.activate();
        admin.setPasswordHash(passwordEncoder.encode(configuredPassword));
        // Pre-enrol TOTP: set the pending secret then promote it to active (mfaEnabled=true) — mirrors
        // TotpService.activate, so the staff second factor is satisfiable at login with reproducible codes.
        admin.setMfaPendingSecret(DEV_TOTP_SECRET);
        admin.enableMfa();
        return userRepository.save(admin);
    }

    /**
     * Logs the dev login recipe at WARN with an unmissable "DEV ONLY" banner. WHY WARN (not INFO): a known
     * admin credential is a security-relevant fact a developer must see and an operator must never see in a
     * non-dev environment — and this code only runs under the {@code dev} profile, so a WARN here is itself a
     * signal if it ever appears in the wrong environment's logs.
     */
    private void logDevCredentialsBanner() {
        // Build the JSON literals separately so SLF4J's {} placeholders are never adjacent to literal
        // braces (SLF4J only substitutes a bare "{}", and "{{" would render literally). ASCII-only so the
        // banner is clean in every console encoding.
        String loginBody = "{ \"accountKey\": \"" + DEV_ADMIN_PHONE
                + "\", \"password\": \"" + configuredPassword + "\" }";
        String otpauthUri = "otpauth://totp/Taarifu:dev-admin?secret=" + DEV_TOTP_SECRET
                + "&issuer=Taarifu&algorithm=SHA1&digits=6&period=30";
        String totpBody = "{ \"mfaToken\": \"<from the previous response>\", \"totp\": \"<6-digit code>\" }";
        log.warn("""

                ============================================================================
                 DEV ONLY - bootstrap admin account created (never in production).
                 Profile 'dev' is active; this seeder does not run under any other profile.
                 ----------------------------------------------------------------------------
                 Login:    POST /api/v1/auth/login/password
                   body:   {}
                 Then:     staff TOTP second factor is required (this is a ROOT account).
                   secret: {}   (otpauth URI below - add to Google Authenticator)
                   {}
                   finish: POST /api/v1/auth/login/totp
                           {}
                ============================================================================""",
                loginBody, DEV_TOTP_SECRET, otpauthUri, totpBody);
    }
}
