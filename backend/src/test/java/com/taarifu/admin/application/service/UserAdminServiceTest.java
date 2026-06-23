package com.taarifu.admin.application.service;

import com.taarifu.admin.api.spi.AdminPage;
import com.taarifu.admin.api.spi.AdminUserView;
import com.taarifu.admin.api.spi.IdentityAdminPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.ScopeGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Unit tests for {@link UserAdminService} — the admin user/role workflow and its conflict-of-interest
 * fence (M14, US-14.1; D16).
 *
 * <p>Responsibility: proves (a) every mutating action is blocked when the admin targets <b>their own</b>
 * account (no self-escalation / self-shielding — D16), and that the port is then NEVER called; (b) a
 * permitted action delegates to the {@link IdentityAdminPort} with the <b>acting admin from the security
 * context</b> (never a body field); and (c) reads pass through. Each fence test fails if the
 * {@code isNotSelf} guard were removed (CLAUDE.md §10). No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private IdentityAdminPort identityAdmin;
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
        return new UserAdminService(identityAdmin, scopeGuard);
    }

    @Test
    void grantRole_onSelf_isBlocked_conflictOfInterest_portNeverCalled() {
        authenticateAdmin();
        // The admin targets their OWN account → self-escalation must be blocked (D16).
        when(scopeGuard.isNotSelf(admin)).thenReturn(false);

        assertThatThrownBy(() -> service().grantRole(admin, "MODERATOR"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(identityAdmin, never()).grantRole(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void suspend_onSelf_isBlocked_conflictOfInterest() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(admin)).thenReturn(false);

        assertThatThrownBy(() -> service().suspendUser(admin, "SECURITY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT_OF_INTEREST);

        verify(identityAdmin, never()).suspendUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void grantRole_onAnother_delegates_withActingAdminFromContext() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);

        service().grantRole(target, "RESPONDER_AGENT");

        // The acting-admin id passed to the port is the CALLER's identity, not anything from the body.
        verify(identityAdmin).grantRole(admin, target, "RESPONDER_AGENT");
    }

    @Test
    void revokeRole_onAnother_delegates() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);

        service().revokeRole(target, "MODERATOR");

        verify(identityAdmin).revokeRole(admin, target, "MODERATOR");
    }

    @Test
    void reinstate_onAnother_delegates() {
        authenticateAdmin();
        when(scopeGuard.isNotSelf(target)).thenReturn(true);

        service().reinstateUser(target);

        verify(identityAdmin).reinstateUser(admin, target);
    }

    @Test
    void listUsers_passesThroughToPort() {
        AdminUserView view = new AdminUserView(target, "+2557****1234", "mwananchi", "ACTIVE", "T2",
                List.of("CITIZEN"), java.time.Instant.parse("2026-01-01T00:00:00Z"));
        when(identityAdmin.listUsers("kata", "CITIZEN", "ACTIVE", 0, 20))
                .thenReturn(new AdminPage<>(List.of(view), 0, 20, 1L));

        AdminPage<AdminUserView> result = service().listUsers("kata", "CITIZEN", "ACTIVE", 0, 20);

        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.content()).singleElement()
                .satisfies(v -> assertThat(v.maskedPhone()).isEqualTo("+2557****1234"));
    }
}
