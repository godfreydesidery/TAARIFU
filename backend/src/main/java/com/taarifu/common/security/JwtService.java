package com.taarifu.common.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and verifies Taarifu JWTs (access + rotating refresh) — ADR-0007, ARCHITECTURE.md §6.1.
 *
 * <p>Responsibility: a correct, self-contained signing/verification skeleton. It mints tokens whose
 * <b>subject is the user's immutable {@code publicId} (UUID)</b> — never a mutable username (ADR-0006)
 * — and stamps a {@link TokenType} claim that is re-validated on every use to prevent refresh↔access
 * confusion. Roles and trust tier are embedded as claims but treated as <b>hints</b>: high-stakes
 * authorization re-checks server-side (PRD §17, §18).</p>
 *
 * <p>WHY HMAC here (and what changes later): the scaffold signs with HS256 over a secret from the
 * environment so the auth path is buildable and testable with no key infrastructure. ADR-0007's
 * target is asymmetric RS256/ES256 for key rotation/publication; swapping the signer/verifier is a
 * localised change confined to this class — callers (the filter, the auth controller) are unaffected.
 * The signing secret is injected, never hard-coded (PRD §18, CLAUDE.md §12).</p>
 */
@Service
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TRUST_TIER = "trustTier";

    private final JwtProperties properties;

    /**
     * @param properties externalised signing secret + lifetimes (from env/secret manager).
     */
    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    /**
     * Mints a signed access token.
     *
     * @param subjectPublicId the authenticated user's {@code publicId} (JWT {@code sub}).
     * @param roles           the user's role names (claim hint; re-checked server-side).
     * @param trustTier       the user's trust tier name T0–T3 (claim hint; re-checked server-side).
     * @return the serialised compact JWT.
     */
    public String issueAccessToken(UUID subjectPublicId, List<String> roles, String trustTier) {
        return sign(subjectPublicId, TokenType.ACCESS, roles, trustTier, properties.accessTtl().getSeconds());
    }

    /**
     * Mints a signed refresh token (no role/tier claims — it only authorises a rotation).
     *
     * @param subjectPublicId the user's {@code publicId}.
     * @return the serialised compact JWT.
     */
    public String issueRefreshToken(UUID subjectPublicId) {
        return sign(subjectPublicId, TokenType.REFRESH, List.of(), null, properties.refreshTtl().getSeconds());
    }

    /**
     * Verifies a token's signature and expiry, and asserts it is of the expected {@link TokenType}.
     *
     * @param token        the compact JWT.
     * @param expectedType the type the caller requires ({@link TokenType#ACCESS} for the filter,
     *                     {@link TokenType#REFRESH} for the refresh endpoint).
     * @return the parsed, validated claims.
     * @throws JwtVerificationException if the signature is invalid, the token is expired, or its
     *                                  {@code tokenType} does not match {@code expectedType}.
     */
    public ParsedToken verify(String token, TokenType expectedType) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secretBytes()))) {
                throw new JwtVerificationException("Invalid token signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) {
                throw new JwtVerificationException("Token expired");
            }
            String typeClaim = claims.getStringClaim(CLAIM_TOKEN_TYPE);
            if (typeClaim == null || !typeClaim.equals(expectedType.name())) {
                // WHY: a refresh token must never authorise an API call, and vice versa (ADR-0007).
                throw new JwtVerificationException("Unexpected token type: " + typeClaim);
            }
            return new ParsedToken(
                    UUID.fromString(claims.getSubject()),
                    castRoles(claims.getStringListClaim(CLAIM_ROLES)),
                    claims.getStringClaim(CLAIM_TRUST_TIER));
        } catch (ParseException | JOSEException e) {
            throw new JwtVerificationException("Token verification failed", e);
        }
    }

    /** Signs a claims set with HS256. */
    private String sign(UUID subject, TokenType type, List<String> roles, String trustTier, long ttlSeconds) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(subject.toString())
                    .issuer(properties.issuer())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                    .claim(CLAIM_TOKEN_TYPE, type.name());
            if (!roles.isEmpty()) {
                builder.claim(CLAIM_ROLES, roles);
            }
            if (trustTier != null) {
                builder.claim(CLAIM_TRUST_TIER, trustTier);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(secretBytes()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new JwtVerificationException("Token signing failed", e);
        }
    }

    private byte[] secretBytes() {
        return properties.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castRoles(List<String> roles) {
        return roles == null ? List.of() : roles;
    }

    /**
     * Validated token contents handed to the authentication filter.
     *
     * @param subject   the user's {@code publicId} (becomes the security principal).
     * @param roles     role-name hints from the token.
     * @param trustTier trust-tier hint from the token.
     */
    public record ParsedToken(UUID subject, List<String> roles, String trustTier) {
    }
}
