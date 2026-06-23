package com.taarifu.identity.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * The N-4 staff second-factor gate (VERIFICATION-DESIGN §7). It answers two questions, both deny-by-default:
 *
 * <ul>
 *   <li>{@link #requiresSecondFactor(User)} — used by {@code LoginService}: must this login complete a
 *       TOTP step before a real token pair is issued? True if the account has MFA enabled <b>or</b> holds
 *       any <b>active and effective</b> staff {@link RoleAssignment} (MODERATOR/ADMIN/ROOT).</li>
 *   <li>{@link #isStaffMfaSatisfied()} — the {@code @mfa} method-security bean used by the Moderator
 *       endpoints: is the <i>current</i> session allowed onto the staff surface? True only when the
 *       authenticated caller holds a live staff role <b>and</b> {@code mfaEnabled=true} — so a stale or
 *       citizen-only session (or a staff account that never enrolled TOTP) is refused.</li>
 * </ul>
 *
 * <p>WHY both a login gate and an endpoint gate (defence in depth, §7.2): the login gate prevents a staff
 * token being minted without TOTP; the endpoint gate prevents a session that somehow carries a staff
 * authority (e.g. minted on a refresh after a later grant) from reaching the staff surface without MFA.
 * Either alone would leave a gap. The role check uses the effective-window-aware query (N-2) so a lapsed
 * staff mandate neither forces MFA nor satisfies the staff gate.</p>
 *
 * <p>Registered as the Spring bean named {@code mfa} so method security can reference it:
 * {@code @PreAuthorize("hasRole('MODERATOR') and @mfa.isStaffMfaSatisfied()")}.</p>
 */
@Component("mfa")
public class MfaLoginGate {

    /** Roles whose holders must hold a TOTP second factor before acting as staff (N-4). */
    private static final Set<RoleName> STAFF_ROLES =
            Set.of(RoleName.MODERATOR, RoleName.ADMIN, RoleName.ROOT);

    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserRepository userRepository;
    private final ClockPort clock;

    /**
     * Whether the staff TOTP second factor is enforced. <b>Defaults to {@code true}</b> — production and
     * any unset environment MUST keep MFA on. It exists ONLY as a local-dev/test escape hatch
     * ({@code TAARIFU_MFA_ENFORCED=false}) so the bootstrap admin can be exercised without an authenticator
     * app; it is never to be disabled in a real deployment (N-4, VERIFICATION-DESIGN §7). When {@code false}
     * a staff login completes on the first factor alone and the staff-endpoint gate skips the MFA re-check.
     */
    private final boolean mfaEnforced;

    /**
     * @param roleAssignmentRepository the live, effective-window-aware role source (N-2, MF-3).
     * @param userRepository           account lookup for the live {@code mfaEnabled} re-check.
     * @param clock                    time source for the effective-window check (testable).
     * @param mfaEnforced              {@code taarifu.security.mfa.enforced} (default {@code true}); set
     *                                 {@code false} ONLY for local testing — keep {@code true} in prod.
     */
    public MfaLoginGate(RoleAssignmentRepository roleAssignmentRepository,
                        UserRepository userRepository,
                        ClockPort clock,
                        @Value("${taarifu.security.mfa.enforced:true}") boolean mfaEnforced) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.mfaEnforced = mfaEnforced;
    }

    /**
     * @param user the account whose first login factor just succeeded.
     * @return {@code true} if login must complete a TOTP step before issuing a real token pair (the
     *         account has MFA enabled, or holds an active+effective staff role).
     */
    @Transactional(readOnly = true)
    public boolean requiresSecondFactor(User user) {
        // Local-dev/test escape hatch only (default true everywhere else).
        if (!mfaEnforced) {
            return false;
        }
        return user.isMfaEnabled() || holdsStaffRole(user.getPublicId());
    }

    /**
     * The {@code @mfa.isStaffMfaSatisfied()} method-security gate for staff endpoints. Deny-by-default:
     * with no authenticated principal it returns {@code false}.
     *
     * @return {@code true} only when the current caller holds a live staff role <b>and</b> has MFA
     *         enabled (a TOTP step was completed at login — N-4); {@code false} otherwise.
     */
    @Transactional(readOnly = true)
    public boolean isStaffMfaSatisfied() {
        return CurrentUser.current()
                .map(CurrentUser::publicId)
                .map(this::staffMfaSatisfiedFor)
                .orElse(false);
    }

    /** Live re-check: the account exists, has MFA enabled, and currently holds an effective staff role. */
    private boolean staffMfaSatisfiedFor(UUID userPublicId) {
        return userRepository.findByPublicId(userPublicId)
                .filter(u -> !mfaEnforced || u.isMfaEnabled())
                .map(u -> holdsStaffRole(userPublicId))
                .orElse(false);
    }

    /** @return whether the account holds any active+effective staff role grant (N-2 window-aware). */
    private boolean holdsStaffRole(UUID userPublicId) {
        for (RoleAssignment ra : roleAssignmentRepository
                .findActiveEffectiveByUser(userPublicId, clock.now())) {
            if (ra.getRole() != null && STAFF_ROLES.contains(ra.getRole().getName())) {
                return true;
            }
        }
        return false;
    }
}
