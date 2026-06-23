package com.taarifu.admin.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import com.taarifu.identity.api.UserAdminApi;
import com.taarifu.identity.api.UserAdminQueryApi;
import com.taarifu.identity.api.dto.GrantRoleCommand;
import com.taarifu.identity.api.dto.UserAdminFilter;
import com.taarifu.identity.api.dto.UserAdminPage;
import com.taarifu.identity.api.dto.UserAdminSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin {@link UserAdminService} — the operator workflow and its conflict-of-interest
 * fence (M14, US-14.1; D16).
 *
 * <p>Responsibility: proves (a) every mutating action is blocked when the admin targets <b>their own</b>
 * account (no self-escalation / self-shielding — D16), and the identity command port is then NEVER called;
 * (b) a permitted action delegates to the identity {@link UserAdminApi} with the <b>acting admin from the
 * security context</b> (never a body field) and propagates the returned assignment id; and (c) reads pass
 * through to the identity {@link UserAdminQueryApi}. Each fence test fails if the {@code isNotSelf} guard were
 * removed (CLAUDE.md §10). No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private UserAdminQueryApi userQuery;
    @Mock
    private UserAdminApi userAdmin;
    @Mock
    private ScopeGuard scopeGuard;

    private final UUID admin = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates the acting admin in the security context (the controller's @PreAuthorize already passed). */
    private void authenticateAdmin() {
        CurrentUser principal = new CurrentUser(admin, List.of("ADMIN"), "T3");
        var auth = new UsernamePasswordAuthenticationToken(admin, null, List.of());
        auth.setDetails(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private UserAdminService service() {
        return new UserAdminService(userQuery, userAdmin, scopeGuard);
    }

    private GrantRoleCommand grantCmd(String role) {
        return new GrantRoleCommand(role, null, null, null, null, null);
    }

    @Test
    void grantRole_onSelf_isBlocked_conflictOfInterest_portNeverCalled() {
        authenticateAdmin();
        // The admin targets their OWN account → self-escalation must be blocked (D16).
        when(scopeGuard.isNotSelf(admin)).thenReturn(false);

        assertThatThrownBy(() -> service().grantRole(admin, grantCmd("MODERATOR")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(userAdmin, never()).grantRole(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    void suspend_onSelf_isBlocked_conflictOfInterest() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(admin)).thenReturn(false);

        assertThatThrownBy(() -> service().suspendUser(admin, "SECURITY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(userAdmin, never()).suspendUser(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    void revokeRole_onSelf_isBlocked_conflictOfInterest_portNeverCalled() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(admin)).thenReturn(false);
        UUID assignmentId = UUID.randomUUID();

        assertThatThrownBy(() -> service().revokeRole(admin, assignmentId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(userAdmin, never()).revokeRole(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    void grantRole_onAnother_delegates_withActingAdminFromContext_andReturnsAssignmentId() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);
        UUID assignmentId = UUID.randomUUID();
        GrantRoleCommand cmd = grantCmd("RESPONDER_AGENT");
        when(userAdmin.grantRole(admin, target, cmd)).thenReturn(assignmentId);

        UUID result = service().grantRole(target, cmd);

        // The acting-admin id passed to the port is the CALLER's identity, not anything from the body.
        assertThat(result).isEqualTo(assignmentId);
        verify(userAdmin).grantRole(admin, target, cmd);
    }

    @Test
    void revokeRole_onAnother_delegates_byAssignmentId() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);
        UUID assignmentId = UUID.randomUUID();

        service().revokeRole(target, assignmentId);

        verify(userAdmin).revokeRole(admin, target, assignmentId);
    }

    @Test
    void reinstate_onAnother_delegates() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);

        service().reinstateUser(target);

        verify(userAdmin).reinstateUser(admin, target);
    }

    @Test
    void listUsers_passesThroughToQueryPort() {
        UserAdminSummary view = new UserAdminSummary(target, "Asha Mwananchi", "+2557****1234", "T2",
                "ACTIVE", List.of("CITIZEN"), java.time.Instant.parse("2026-01-01T00:00:00Z"));
        UserAdminFilter filter = new UserAdminFilter("asha", null, null, "CITIZEN", "ACTIVE");
        when(userQuery.listUsers(filter, 0, 20))
                .thenReturn(new UserAdminPage<>(List.of(view), 0, 20, 1L));

        UserAdminPage<UserAdminSummary> result = service().listUsers(filter, 0, 20);

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.content()).singleElement()
                .satisfies(v -> assertThat(v.maskedPhone()).isEqualTo("+2557****1234"));
    }
}
