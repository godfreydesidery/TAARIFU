package com.taarifu.identity.infrastructure.bootstrap;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
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
import com.taarifu.identity.infrastructure.totp.Base32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Production-safe, <b>explicitly-gated</b> bootstrap of the <b>first</b> {@link RoleName#ROOT} account
 * (threat-model item E / TR-5 / G16 — "Prod-safe first-admin provisioning").
 *
 * <p><b>WHY this exists and why it is NOT {@code @Profile}-gated</b> (contrast {@link DevAdminSeeder}).
 * {@link DevAdminSeeder} is {@code @Profile("dev")} and ships a <i>known</i> credential — perfect for a
 * laptop, unacceptable for production (a standing backdoor; CLAUDE.md §12, PRD §18). Yet a fresh
 * production database has <b>zero accounts</b>, so there is no one to perform the audited admin-grant path:
 * a chicken-and-egg. This component closes that gap <b>without ever shipping a default credential</b>. It
 * is gated by an <b>explicit operator opt-in config flag</b>
 * {@code taarifu.bootstrap.admin.enabled=true} (env {@code TAARIFU_BOOTSTRAP_ADMIN_ENABLED}) via
 * {@link ConditionalOnProperty} — <b>not</b> a profile — so it can run in any environment <i>only when an
 * operator deliberately turns it on</i> for a single, audited first-boot, then turns it off again. With the
 * flag absent/false the bean is never instantiated (no runner, no account), exactly like a disabled
 * adapter.</p>
 *
 * <p><b>Fail-safe by construction (never a default/hardcoded credential).</b> Even when enabled, the
 * runner does nothing unless the operator has supplied a <b>strong</b> phone + password via the
 * environment ({@code TAARIFU_BOOTSTRAP_ADMIN_PHONE} / {@code TAARIFU_BOOTSTRAP_ADMIN_PASSWORD}). A blank
 * or weak password (see {@link #isStrongPassword(String)}) is refused with a WARN and <b>no account is
 * created</b> — there is deliberately no fallback constant here (the dev seeder's
 * {@code DEFAULT_DEV_ADMIN_PASSWORD} has no equivalent in this class). The password is <b>BCrypt</b>-hashed
 * via the shared {@link PasswordEncoder} before storage; the plaintext is <b>never logged</b> (S-4).</p>
 *
 * <p><b>Idempotent (no-op if any ROOT already exists).</b> The very first guard is "does any account
 * already hold an {@link RoleStatus#ACTIVE} {@link RoleName#ROOT} grant?" — if so the runner returns
 * immediately (logged at INFO). This makes the flag safe to leave enabled by mistake across reboots and
 * safe to run against a database that already has a real ROOT: it can never mint a <b>second</b>
 * super-administrator. The configured-phone-already-taken case is likewise a no-op.</p>
 *
 * <p><b>Staff MFA — avoiding the login deadlock, without logging a secret.</b> Any ROOT account is forced
 * through the TOTP second factor by {@code MfaLoginGate#requiresSecondFactor} (N-4): a staff account with
 * no <b>active</b> TOTP secret returns an MFA challenge at password login that it can never satisfy
 * ({@code TotpService#verify} rejects when the active secret is {@code null}) — a hard deadlock. So this
 * bootstrap <b>provisions and activates</b> a freshly-generated 160-bit TOTP secret (so first login is
 * <i>not</i> deadlocked), and surfaces the enrolment material to the operator strictly <b>out of band</b>:
 * the {@code otpauth://} provisioning URI is written <b>once</b> to a {@code 0600} file on disk
 * (default {@value #DEFAULT_ENROLLMENT_FILE}, overridable via
 * {@code taarifu.bootstrap.admin.totp-enrollment-file}). The raw secret is <b>never written to the
 * application log</b> (S-4) — only the file path is logged at WARN, with an instruction to enrol from the
 * file and then <b>delete it and rotate the password</b>. This is the prod analogue of the dev seeder's
 * banner, minus the published test-vector (which would be a real secret here).</p>
 *
 * <p><b>What it creates</b> (idempotently), reusing the same identity factories as every normal flow so the
 * account is byte-for-byte a normal account:</p>
 * <ul>
 *   <li>an {@link com.taarifu.identity.domain.model.enums.UserStatus#ACTIVE ACTIVE} {@link User} at the
 *       operator-supplied phone, BCrypt password, TOTP enrolled (active secret);</li>
 *   <li>a phone-verified {@link Profile} (the 1:1 invariant);</li>
 *   <li>the additive base {@link RoleName#CITIZEN} grant (§6.4/D12) plus a <b>global-scope</b>
 *       {@link RoleName#ROOT} {@link RoleAssignment} in status {@link RoleStatus#ACTIVE}, effective now;</li>
 *   <li>a cached {@link TrustTier#T2} hint (complete, phone-verified, no ID verification) — the live
 *       resolver still governs gating (MF-2).</li>
 * </ul>
 *
 * <p><b>Audit (R, L-1).</b> A successful bootstrap appends a {@link AuditEventType#ROLE_GRANTED} event
 * (actor = the {@code SYSTEM_ACTOR} sentinel, since no human principal exists yet; subject = the new ROOT
 * account; {@code reason_code = "ROOT:BOOTSTRAP"}) so the canonical "who got ROOT and how" trail exists in
 * the tamper-evident store from the first second. References/public-ids only — never PII (PRD §18, PDPA).</p>
 *
 * <p><b>Security/PDPA notes (S-4).</b> The bootstrap admin carries <b>no national/voter ID</b> (no PII), so
 * nothing field-encrypted or blind-indexed is written. The only secrets in play — the password and the TOTP
 * secret — are never logged: the password is hashed before storage, and the TOTP secret leaves the process
 * only through the restrictive-permission enrolment file the operator consumes once.</p>
 */
@Component
@ConditionalOnProperty(name = "taarifu.bootstrap.admin.enabled", havingValue = "true")
public class ProdAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdAdminBootstrap.class);

    /** Display name for the bootstrap profile (no PII). */
    private static final String ADMIN_FIRST_NAME = "Root";
    private static final String ADMIN_LAST_NAME = "Administrator";

    /** Entropy of the generated TOTP secret (20 bytes = 160-bit, the authenticator default). */
    private static final int TOTP_SECRET_BYTES = 20;
    private static final String TOTP_ISSUER = "Taarifu";

    /**
     * Minimum password length accepted by {@link #isStrongPassword(String)}. WHY 12: a first-ROOT
     * credential is the highest-value secret on the platform; this is a floor that refuses obviously weak
     * input, not a substitute for the operator using a generated passphrase (the WARN says to rotate it).
     */
    static final int MIN_PASSWORD_LENGTH = 12;

    /** Default path for the one-time {@code otpauth://} enrolment file (0600). Overridable via config. */
    static final String DEFAULT_ENROLLMENT_FILE = "./taarifu-root-totp-enrollment.txt";

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventService audit;

    /** Operator-supplied E.164 phone (the account key); {@code null}/blank → no-op. Never the source of a default. */
    private final String configuredPhone;

    /** Operator-supplied strong password; {@code null}/blank/weak → no-op. BCrypt-hashed, never logged. */
    private final String configuredPassword;

    /** Where the one-time {@code otpauth://} enrolment URI is written (0600); default {@value #DEFAULT_ENROLLMENT_FILE}. */
    private final String enrollmentFilePath;

    /**
     * @param userRepository           account persistence + the one-account-per-phone idempotency check.
     * @param profileRepository        profile persistence (the 1:1 invariant).
     * @param roleRepository           role-catalogue lookup (CITIZEN + ROOT rows, seeded in V102).
     * @param roleAssignmentRepository the additive CITIZEN grant + the global-scope ROOT grant; also the
     *                                 existing-ROOT idempotency check.
     * @param passwordEncoder          the shared BCrypt encoder (never stores plaintext — ADR-0007).
     * @param audit                    append-only audit writer (the {@code ROLE_GRANTED} bootstrap trail).
     * @param configuredPhone          {@code TAARIFU_BOOTSTRAP_ADMIN_PHONE} — required; no default.
     * @param configuredPassword       {@code TAARIFU_BOOTSTRAP_ADMIN_PASSWORD} — required + strong; no default.
     * @param enrollmentFilePath       {@code taarifu.bootstrap.admin.totp-enrollment-file}; default
     *                                 {@value #DEFAULT_ENROLLMENT_FILE}.
     */
    public ProdAdminBootstrap(UserRepository userRepository,
                              ProfileRepository profileRepository,
                              RoleRepository roleRepository,
                              RoleAssignmentRepository roleAssignmentRepository,
                              PasswordEncoder passwordEncoder,
                              AuditEventService audit,
                              @Value("${taarifu.bootstrap.admin.phone:}") String configuredPhone,
                              @Value("${taarifu.bootstrap.admin.password:}") String configuredPassword,
                              @Value("${taarifu.bootstrap.admin.totp-enrollment-file:"
                                      + DEFAULT_ENROLLMENT_FILE + "}") String enrollmentFilePath) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.configuredPhone = configuredPhone;
        this.configuredPassword = configuredPassword;
        this.enrollmentFilePath = enrollmentFilePath;
    }

    /**
     * Creates the first ROOT account once at startup, idempotently and fail-safe.
     *
     * <p>Order of guards (all no-ops, never an exception that would fail the boot — a misconfigured
     * bootstrap must never take the platform down):</p>
     * <ol>
     *   <li>any ACTIVE ROOT already exists → skip (idempotent);</li>
     *   <li>phone or password missing/weak → skip (no default credential — fail-safe);</li>
     *   <li>the configured phone is already taken → skip (one account per phone — D11/D15).</li>
     * </ol>
     *
     * @param args the application arguments (unused).
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (anyRootExists()) {
            log.info("ProdAdminBootstrap: an ACTIVE ROOT account already exists — skipping first-admin "
                    + "bootstrap (idempotent). Disable taarifu.bootstrap.admin.enabled.");
            return;
        }
        if (isBlank(configuredPhone) || !isStrongPassword(configuredPassword)) {
            // Fail-safe: enabled but not properly configured. NEVER fabricate a default credential.
            log.warn("ProdAdminBootstrap: enabled but TAARIFU_BOOTSTRAP_ADMIN_PHONE / "
                    + "TAARIFU_BOOTSTRAP_ADMIN_PASSWORD are missing or the password is too weak "
                    + "(min {} chars, mixed). No account created — provide a strong credential and reboot.",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        if (userRepository.existsByPhone(configuredPhone)) {
            log.warn("ProdAdminBootstrap: the configured bootstrap phone is already registered — "
                    + "skipping (one account per phone). Grant ROOT to that account via the audited path "
                    + "instead.");
            return;
        }

        Role citizenRole = requireRole(RoleName.CITIZEN);
        Role rootRole = requireRole(RoleName.ROOT);
        if (citizenRole == null || rootRole == null) {
            // The role catalogue is seeded by migration V102; if it is absent the DB is not migrated. Do not
            // fabricate catalogue rows in prod — fail safe and tell the operator to run migrations first.
            log.warn("ProdAdminBootstrap: the role catalogue (CITIZEN/ROOT) is not present — run Flyway "
                    + "migrations before bootstrapping. No account created.");
            return;
        }

        String totpSecret = Base32.randomSecret(TOTP_SECRET_BYTES);
        User admin = createActiveRootAccount(totpSecret);

        Profile profile = Profile.createPersonForSignup(admin);
        profile.updateDetails(ADMIN_FIRST_NAME, ADMIN_LAST_NAME, null, null, null);
        profileRepository.save(profile);

        // Additive roles on one account (§6.4/D12): keep the base CITIZEN grant alongside the staff ROOT role.
        roleAssignmentRepository.save(RoleAssignment.grant(admin, citizenRole, RoleStatus.ACTIVE));
        // Global scope = no area/category/constituency restriction (unrestricted ROOT), effective now.
        roleAssignmentRepository.save(RoleAssignment.grant(admin, rootRole, RoleStatus.ACTIVE));

        // Cached tier hint only (MF-2 still re-resolves live): complete, phone-verified, no ID verification → T2.
        admin.setTrustTier(TrustTier.T2);

        // Out-of-band enrolment material (never to the application log — S-4) + the canonical audit trail.
        writeEnrollmentFile(admin, totpSecret);
        auditBootstrap(admin);
        logOperatorBanner(admin);
    }

    /**
     * @return {@code true} if any account already holds an {@link RoleStatus#ACTIVE} {@link RoleName#ROOT}
     *         grant — the idempotency guard that prevents a second super-administrator ever being minted.
     *         Scans grants (the first-boot table is tiny and this runs once, at startup — a dedicated count
     *         query would be premature, KISS).
     */
    private boolean anyRootExists() {
        return roleAssignmentRepository.findAll().stream()
                .anyMatch(ra -> ra.getRole() != null
                        && ra.getRole().getName() == RoleName.ROOT
                        && ra.getStatus() == RoleStatus.ACTIVE);
    }

    /** @return the catalogue row for {@code name}, or {@code null} if migrations have not seeded it (V102). */
    private Role requireRole(RoleName name) {
        return roleRepository.findByName(name).orElse(null);
    }

    /**
     * Builds the ACTIVE ROOT account: BCrypt password, and TOTP enrolled with the supplied fresh secret via
     * the same pending→active promotion {@code TotpService.activate} performs (so the active secret +
     * {@code mfaEnabled} are set exactly as a real enrolment leaves them, and the staff MFA gate is
     * satisfiable at first login — no deadlock).
     */
    private User createActiveRootAccount(String totpSecret) {
        User admin = User.createPending(configuredPhone);
        admin.activate();
        admin.setPasswordHash(passwordEncoder.encode(configuredPassword));
        admin.setMfaPendingSecret(totpSecret);
        admin.enableMfa();
        return userRepository.save(admin);
    }

    /**
     * Validates a candidate first-ROOT password. Refuses blank, short (&lt; {@value #MIN_PASSWORD_LENGTH}),
     * or single-character-class passwords. This is a fail-safe floor (an obviously weak credential is
     * rejected rather than silently accepted) — the operator is still told to rotate it immediately.
     *
     * @param password the operator-supplied password, possibly {@code null}.
     * @return {@code true} if the password clears the minimum strength floor.
     */
    static boolean isStrongPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return false;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasNonLetter = password.chars().anyMatch(c -> !Character.isLetter(c));
        return hasLetter && hasNonLetter;
    }

    /**
     * Writes the {@code otpauth://} enrolment URI to a {@code 0600} file the operator consumes <b>once</b>,
     * so the TOTP secret never reaches the application log (S-4). On a filesystem that does not support POSIX
     * permissions (e.g. Windows), the file is still written and a WARN notes that permissions could not be
     * restricted — the operator must protect/delete it. A failure to write must NOT fail the bootstrap (the
     * account is already created); it is logged so the operator can recover the secret by resetting MFA.
     */
    private void writeEnrollmentFile(User admin, String totpSecret) {
        String otpauthUri = "otpauth://totp/" + TOTP_ISSUER + ":" + admin.getPublicId()
                + "?secret=" + totpSecret + "&issuer=" + TOTP_ISSUER + "&algorithm=SHA1&digits=6&period=30";
        String body = "Taarifu first-ROOT TOTP enrolment (DELETE THIS FILE after enrolling).\n"
                + "Add to an authenticator app, then remove this file and rotate the password.\n"
                + otpauthUri + "\n";
        Path path = Path.of(enrollmentFilePath);
        try {
            Files.writeString(path, body, StandardCharsets.UTF_8);
            restrictToOwnerOnly(path);
        } catch (IOException ex) {
            // Do not fail the boot: the account exists; the operator can reset MFA to obtain a new secret.
            log.warn("ProdAdminBootstrap: could not write the TOTP enrolment file at {} ({}). Reset MFA for "
                    + "the ROOT account to obtain enrolment material.", path.toAbsolutePath(), ex.getMessage());
        }
    }

    /** Best-effort {@code 0600} on the enrolment file; logs (does not fail) when POSIX perms are unsupported. */
    private void restrictToOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException ex) {
            // Non-POSIX filesystem (e.g. Windows): the file is written but not restricted to 0600.
            log.warn("ProdAdminBootstrap: could not set 0600 on {} ({}) — protect or delete it manually.",
                    path.toAbsolutePath(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Appends the canonical {@link AuditEventType#ROLE_GRANTED} trail for the first ROOT. Actor is the
     * {@code SYSTEM_ACTOR} sentinel (no human principal exists at first boot); subject is the new account;
     * reason {@code "ROOT:BOOTSTRAP"} distinguishes it from an admin-driven grant. References only — no PII.
     */
    private void auditBootstrap(User admin) {
        audit.record(AuditEvent.Builder
                .of(AuditEventType.ROLE_GRANTED, AuditOutcome.SUCCESS)
                .actor(com.taarifu.common.persistence.AuditorAwareImpl.SYSTEM_ACTOR)
                .subject(admin.getPublicId())
                .roles("SYSTEM")
                .reason("ROOT:BOOTSTRAP")
                .build());
    }

    /**
     * Logs the operator banner at WARN: where the enrolment file is and the mandatory follow-up (enrol, then
     * delete the file and rotate the password). WHY WARN: minting the first ROOT is a security-relevant fact
     * an operator must see and act on. The phone is an account key (not sensitive PII like an ID); the
     * password and TOTP secret are never logged.
     */
    private void logOperatorBanner(User admin) {
        log.warn("""

                ============================================================================
                 PROD-SAFE BOOTSTRAP - first ROOT account created (opt-in, one-time).
                 ----------------------------------------------------------------------------
                 Account:  {}  (ROOT)
                 MFA:      TOTP enrolment URI written to: {}
                 NEXT:     1) add the URI to an authenticator app
                           2) DELETE the enrolment file
                           3) log in and ROTATE THE PASSWORD immediately
                           4) set taarifu.bootstrap.admin.enabled=false and reboot
                ============================================================================""",
                admin.getPhone(), Path.of(enrollmentFilePath).toAbsolutePath());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
