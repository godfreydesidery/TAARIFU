package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditOutcome;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and rotates the access + refresh token pair, with reuse-detection → family revocation
 * (AUTH-DESIGN §5, ADR-0011 §2, S-3).
 *
 * <p>Responsibility: the single owner of token lifecycle. On login/signup it issues a short access JWT
 * plus a <b>rotating, single-use</b> refresh JWT persisted <b>hashed</b> in a fresh family. On refresh
 * it enforces the rotation invariant: a {@code used=true} token re-presented <b>revokes the whole
 * family</b> (theft signal) and fails closed; a valid token rotates to exactly one new live token under a
 * row write-lock so two concurrent refreshes cannot both succeed. Refresh tokens carry no roles/tier.</p>
 *
 * <p>Every decision is audited ({@link AuditEventType#AUTH_TOKEN_REFRESHED},
 * {@link AuditEventType#AUTH_REFRESH_REUSE_DETECTED}, {@link AuditEventType#AUTH_FAMILY_REVOKED}, …) with
 * references only — never a raw token (only its hash is ever stored or referenced, PRD §18).</p>
 */
@Service
public class TokenService {

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final UserRepository userRepository;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param jwtService               token signer/verifier.
     * @param jwtProperties            lifetimes (refresh TTL for the persisted row).
     * @param refreshTokenRepository   persisted hashed refresh tokens.
     * @param roleAssignmentRepository active role names for the access-token hint claim.
     * @param userRepository           account lookup on refresh.
     * @param audit                    append-only audit writer.
     * @param clock                    time source (testable).
     */
    public TokenService(JwtService jwtService,
                        JwtProperties jwtProperties,
                        RefreshTokenRepository refreshTokenRepository,
                        RoleAssignmentRepository roleAssignmentRepository,
                        UserRepository userRepository,
                        AuditEventService audit,
                        ClockPort clock) {
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenRepository = refreshTokenRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.userRepository = userRepository;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Issues a fresh access + refresh pair in a <b>new</b> rotation family (login/signup).
     *
     * @param user the authenticated account.
     * @return the issued token pair (raw tokens — returned to the client, never logged).
     */
    @Transactional
    public TokenPair issuePair(User user) {
        UUID familyId = UUID.randomUUID();
        return mintPair(user, familyId);
    }

    /**
     * Rotates a presented refresh token (AUTH-DESIGN §5.1). Enforces, in one transaction under a row
     * write-lock:
     * <ol>
     *   <li>signature/expiry/type/iss/aud (via {@link JwtService});</li>
     *   <li>the stored row exists and is not revoked/expired;</li>
     *   <li><b>reuse-detection</b>: a {@code used=true} row revokes the entire family and fails closed;</li>
     *   <li>otherwise mark the row used and mint exactly one new live token in the same family.</li>
     * </ol>
     *
     * @param rawRefreshToken the raw refresh JWT presented by the client.
     * @return the new token pair.
     * @throws ApiException {@link ErrorCode#UNAUTHENTICATED} for unknown/forged/revoked/expired tokens;
     *                      {@link ErrorCode#FORBIDDEN} on reuse-detection (force re-login).
     */
    @Transactional
    public TokenPair rotate(String rawRefreshToken) {
        UUID subject;
        try {
            subject = jwtService.verify(rawRefreshToken, TokenType.REFRESH).subject();
        } catch (RuntimeException e) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_REFRESH_REJECTED, AuditOutcome.FAILURE)
                    .reason("INVALID_TOKEN").build());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        String hash = sha256Hex(rawRefreshToken);
        Optional<RefreshToken> rowOpt = refreshTokenRepository.findByTokenHashForUpdate(hash);
        if (rowOpt.isEmpty()) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_REFRESH_REJECTED, AuditOutcome.FAILURE)
                    .actor(subject).reason("UNKNOWN").build());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        RefreshToken row = rowOpt.get();

        if (row.isRevoked()) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_REFRESH_REJECTED, AuditOutcome.FAILURE)
                    .actor(subject).reason("REVOKED").build());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        if (row.isUsed()) {
            // REUSE DETECTED — a consumed token re-presented is a theft signal: revoke the whole family.
            revokeFamily(row.getFamilyId());
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_REFRESH_REUSE_DETECTED, AuditOutcome.DENIED)
                    .actor(subject).reason("REUSE_DETECTED").build());
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (!clock.now().isBefore(row.getExpiresAt())) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_REFRESH_EXPIRED, AuditOutcome.FAILURE)
                    .actor(subject).reason("EXPIRED").build());
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }

        // Single-use rotation: consume the old, mint exactly one new live token in the same family.
        row.markUsed();
        User user = userRepository.findByPublicId(subject)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        TokenPair pair = mintPair(user, row.getFamilyId());
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_TOKEN_REFRESHED, AuditOutcome.SUCCESS)
                .actor(subject).reason("family=" + row.getFamilyId()).build());
        return pair;
    }

    /**
     * Revokes the refresh token presented at logout (single session) — its access token expires naturally.
     *
     * @param rawRefreshToken the refresh token to revoke; unknown/invalid tokens are a silent no-op
     *                        (idempotent logout, never leaks whether it existed).
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken)).ifPresent(row -> {
            row.revoke();
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_LOGOUT, AuditOutcome.SUCCESS)
                    .actor(row.getUser().getPublicId()).build());
        });
    }

    /**
     * Revokes every live refresh family for an account (logout-all / compromise / password change).
     *
     * @param userPublicId the account whose sessions to revoke.
     */
    @Transactional
    public void logoutAll(UUID userPublicId) {
        List<RefreshToken> tokens = refreshTokenRepository.findByUser_PublicId(userPublicId);
        for (RefreshToken t : tokens) {
            if (!t.isRevoked()) {
                t.revoke();
            }
        }
        audit.record(AuditEvent.Builder
                .of(AuditEventType.AUTH_LOGOUT_ALL, AuditOutcome.SUCCESS)
                .actor(userPublicId).build());
    }

    /** Mints + persists a new pair in the given family. */
    private TokenPair mintPair(User user, UUID familyId) {
        List<String> roles = roleAssignmentRepository.findByUser(user).stream()
                .filter(ra -> ra.getRole() != null)
                .map(ra -> ra.getRole().getName().name())
                .distinct()
                .toList();
        String access = jwtService.issueAccessToken(user.getPublicId(), roles, user.getTrustTier().name());
        String refresh = jwtService.issueRefreshToken(user.getPublicId());
        RefreshToken row = RefreshToken.issue(user, sha256Hex(refresh), familyId,
                clock.now().plus(jwtProperties.refreshTtl()));
        refreshTokenRepository.save(row);
        return new TokenPair(access, refresh);
    }

    /** Revokes all live rows in a family (reuse-detection consequence). */
    private void revokeFamily(UUID familyId) {
        List<RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        boolean any = false;
        for (RefreshToken t : family) {
            if (!t.isRevoked()) {
                t.revoke();
                any = true;
            }
        }
        if (any) {
            audit.record(AuditEvent.Builder
                    .of(AuditEventType.AUTH_FAMILY_REVOKED, AuditOutcome.DENIED)
                    .reason("family=" + familyId).build());
        }
    }

    /** SHA-256 hex of a raw token — only the hash is ever stored (PRD §18). */
    static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Token hashing failed", e);
        }
    }

    /**
     * The raw token pair returned to the client.
     *
     * @param accessToken  the short-lived access JWT.
     * @param refreshToken the rotating refresh JWT (the raw value lives only on the client + as a hash here).
     */
    public record TokenPair(String accessToken, String refreshToken) {
    }
}
