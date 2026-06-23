package com.taarifu.identity.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.identity.domain.model.Role;
import com.taarifu.identity.domain.model.RoleAssignment;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.RoleName;
import com.taarifu.identity.domain.model.enums.RoleStatus;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MfaLoginGate#requiresSecondFactor(User)} (N-4, VERIFICATION-DESIGN §7) — pure
 * Mockito, no Spring/Docker.
 *
 * <p>Proves the login-side half of the staff-TOTP gate: a plain citizen does not require a second factor;
 * an account with MFA enabled does; and — the integrity-critical case — an account that holds an
 * <b>active+effective staff role</b> requires the second factor <b>even before it has enrolled</b> (so a
 * staff account can never complete login without TOTP).</p>
 */
class MfaLoginGateTest {

    private RoleAssignmentRepository roleAssignmentRepository;
    private UserRepository userRepository;
    private MfaLoginGate gate;

    @BeforeEach
    void setUp() {
        roleAssignmentRepository = Mockito.mock(RoleAssignmentRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        ClockPort clock = () -> Instant.parse("2026-06-23T00:00:00Z");
        gate = new MfaLoginGate(roleAssignmentRepository, userRepository, clock);
    }

    @Test
    void plainCitizen_doesNotRequireSecondFactor() {
        User citizen = userWith("+255700000201", false);
        when(roleAssignmentRepository.findActiveEffectiveByUser(eq(citizen.getPublicId()), any()))
                .thenReturn(List.of()); // CITIZEN-only / no staff role
        assertThat(gate.requiresSecondFactor(citizen)).isFalse();
    }

    @Test
    void mfaEnabledAccount_requiresSecondFactor() {
        User user = userWith("+255700000202", true);
        when(roleAssignmentRepository.findActiveEffectiveByUser(eq(user.getPublicId()), any()))
                .thenReturn(List.of());
        assertThat(gate.requiresSecondFactor(user)).isTrue();
    }

    @Test
    void staffRoleHolder_requiresSecondFactor_evenBeforeEnrolment() {
        User staff = userWith("+255700000203", false); // not yet enrolled
        // Build the grant BEFORE the outer when(...) so its inner role stubbing does not collide.
        List<RoleAssignment> grants = List.of(moderatorGrant(staff));
        when(roleAssignmentRepository.findActiveEffectiveByUser(eq(staff.getPublicId()), any()))
                .thenReturn(grants);
        // N-4: a staff role forces the second factor regardless of mfaEnabled — they cannot log in as
        // staff until they enrol + complete TOTP.
        assertThat(gate.requiresSecondFactor(staff)).isTrue();
    }

    /** Builds a persisted-looking User (publicId set via reflection — entity ids are otherwise internal). */
    private static User userWith(String phone, boolean mfaEnabled) {
        User u = User.createPending(phone);
        u.activate();
        if (mfaEnabled) {
            u.setMfaPendingSecret("JBSWY3DPEHPK3PXP");
            u.enableMfa();
        }
        setPublicId(u, UUID.randomUUID());
        return u;
    }

    private static RoleAssignment moderatorGrant(User user) {
        Role role = Mockito.mock(Role.class);
        when(role.getName()).thenReturn(RoleName.MODERATOR);
        return RoleAssignment.grant(user, role, RoleStatus.ACTIVE);
    }

    /** Sets the BaseEntity publicId on a transient entity for tests (it is otherwise DB-assigned). */
    private static void setPublicId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("publicId");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
