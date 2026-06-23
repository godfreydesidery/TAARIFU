package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.security.JwtProperties;
import com.taarifu.common.security.JwtService;
import com.taarifu.common.security.TokenType;
import com.taarifu.identity.domain.model.RefreshToken;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.RefreshTokenRepository;
import com.taarifu.identity.domain.repository.RoleAssignmentRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenService} rotation + reuse-detection invariants — S-3 (AUTH-DESIGN §5.1).
 *
 * <p>Responsibility: pins the four load-bearing invariants without a DB (Mockito only): a consumed
 * refresh token re-presented <b>revokes the entire family</b> and fails closed (403); a revoked token
 * fails closed; a valid token rotates to exactly one new live token; only the hash is ever looked up.
 * These are the theft-resistance guarantees the security review demanded be tested.</p>
 */
class TokenServiceTest {

    private JwtService jwtService;
    private RefreshTokenRepository refreshTokenRepository;
    private RoleAssignmentRepository roleAssignmentRepository;
    private UserRepository userRepository;
    private AuditEventService audit;
    private TokenService tokenService;

    private final UUID subject = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(
                "this-is-a-strong-test-secret-of-at-least-32-bytes!!",
                "taarifu", "taarifu-clients",
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(30));
        jwtService = new JwtService(props);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        roleAssignmentRepository = mock(RoleAssignmentRepository.class);
        userRepository = mock(UserRepository.class);
        audit = mock(AuditEventService.class);
        ClockPort clock = Instant::now;

        tokenService = new TokenService(jwtService, props, refreshTokenRepository,
                roleAssignmentRepository, userRepository, audit, clock);

        user = User.createPending("+255700000001");
        user.activate();
        setPublicId(user, subject);
        // N-2: role claims now come from the active-AND-effective query, not the bare findByUser.
        when(roleAssignmentRepository.findActiveEffectiveByUser(any(), any())).thenReturn(List.of());
        when(userRepository.findByPublicId(subject)).thenReturn(Optional.of(user));
    }

    @Test
    void reusingConsumedToken_revokesEntireFamily_andFailsClosed() {
        String rawRefresh = jwtService.issueRefreshToken(subject);
        // The presented token row is already USED (consumed once) — a classic theft signal.
        RefreshToken consumed = RefreshToken.issue(user, TokenService.sha256Hex(rawRefresh), familyId,
                Instant.now().plus(Duration.ofDays(30)));
        consumed.markUsed();
        RefreshToken sibling = RefreshToken.issue(user, "other-hash", familyId,
                Instant.now().plus(Duration.ofDays(30)));

        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex(rawRefresh)))
                .thenReturn(Optional.of(consumed));
        when(refreshTokenRepository.findByFamilyId(familyId)).thenReturn(List.of(consumed, sibling));

        assertThatThrownBy(() -> tokenService.rotate(rawRefresh))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // Every live sibling in the family is revoked (kill-switch on theft).
        assertThat(sibling.isRevoked()).isTrue();
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(any(AuditEvent.class));
    }

    @Test
    void revokedToken_failsClosed() {
        String rawRefresh = jwtService.issueRefreshToken(subject);
        RefreshToken revoked = RefreshToken.issue(user, TokenService.sha256Hex(rawRefresh), familyId,
                Instant.now().plus(Duration.ofDays(30)));
        revoked.revoke();
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex(rawRefresh)))
                .thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> tokenService.rotate(rawRefresh))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void validToken_rotatesToExactlyOneNewLiveToken() {
        String rawRefresh = jwtService.issueRefreshToken(subject);
        RefreshToken live = RefreshToken.issue(user, TokenService.sha256Hex(rawRefresh), familyId,
                Instant.now().plus(Duration.ofDays(30)));
        when(refreshTokenRepository.findByTokenHashForUpdate(TokenService.sha256Hex(rawRefresh)))
                .thenReturn(Optional.of(live));

        TokenService.TokenPair pair = tokenService.rotate(rawRefresh);

        // Old token consumed; a new pair issued; new refresh persisted in the SAME family.
        assertThat(live.isUsed()).isTrue();
        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        // The new access token verifies as ACCESS for the same subject.
        assertThat(jwtService.verify(pair.accessToken(), TokenType.ACCESS).subject()).isEqualTo(subject);
    }

    @Test
    void unknownToken_failsClosed() {
        String rawRefresh = jwtService.issueRefreshToken(subject);
        when(refreshTokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.rotate(rawRefresh))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    /** Reflectively sets the publicId (BaseEntity assigns it on persist; tests bypass the DB). */
    private static void setPublicId(User user, UUID publicId) {
        try {
            var field = Class.forName("com.taarifu.common.domain.model.BaseEntity")
                    .getDeclaredField("publicId");
            field.setAccessible(true);
            field.set(user, publicId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
