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
 * Issues and verifies Taarifu JWTs (access + rotating refresh) — ADR-0007/0011, ARCHITECTURE.md §6.1.
 *
 * <p>Responsibility: a correct, self-contained signing/verification implementation. It mints tokens
 * whose <b>subject is the user's immutable {@code publicId} (UUID)</b> — never a mutable username
 * (ADR-0006) — and stamps a {@link TokenType} claim that is re-validated on every use to prevent
 * refresh↔access confusion. Roles and trust tier are embedded as claims but treated as <b>hints</b>:
 * high-stakes authorization re-checks server-side (PRD §17, §18; the live tier governs — MF-2).</p>
 *
 * <p>JWT hardening (MF-1, ADR-0011 §10): {@link #verify} validates the {@code iss} <b>and</b> {@code aud}
 * claims against the configured values (a token minted for another issuer/audience is rejected), and
 * applies a small clock-skew leeway to expiry and not-before. The secret-strength fail-fast lives in
 * {@link JwtProperties} so a weak/absent secret aborts startup, not a request.</p>
 *
 * <p>WHY HMAC here (and what changes later): the scaffold signs with HS256 over a secret from the
 * environment so the auth path is buildable and testable with no key infrastructure. ADR-0011's target
 * is asymmetric RS256/ES256 behind a {@code JwtKeyProvider}/{@code kid}; swapping the signer/verifier is
 * a localised change confined to this class — callers (the filter, the auth services) are unaffected.</p>
 */
@Service
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TRUST_TIER = "trustTier";

    private final JwtProperties properties;

    /**
     * @param properties externalised signing secret + lifetimes + issuer/audience (from env/secret manager).
     */
    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    /**
     * Mints a signed access token.
     *
     * @param subjectPublicId the authenticated user's {@code publicId} (JWT {@code sub}).
     * @param roles           the user's role names (claim hint; re-checked server-side).
     * @param trustTier       the user's trust tier name T0–T3 (claim hint; re-checked server-side — MF-2).
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
     * Mints a very short-lived {@link TokenType#MFA_CHALLENGE} token for the staff second-factor step
     * (N-4). It carries <b>no roles and no trust tier</b> and is verified like every other token
     * (signature/iss/aud/exp/type), so it can only be presented to {@code POST /auth/login/totp} and
     * never as an access token.
     *
     * @param subjectPublicId the authenticated-by-first-factor user's {@code publicId}.
     * @param ttlSeconds      the challenge lifetime in seconds (~5 min; config-tunable).
     * @return the serialised compact JWT.
     */
    public String issueMfaChallengeToken(UUID subjectPublicId, long ttlSeconds) {
        return sign(subjectPublicId, TokenType.MFA_CHALLENGE, List.of(), null, ttlSeconds);
    }

    /**
     * Verifies a token's signature, expiry, issuer, audience, and {@link TokenType}.
     *
     * @param token        the compact JWT.
     * @param expectedType the type the caller requires ({@link TokenType#ACCESS} for the filter,
     *                     {@link TokenType#REFRESH} for the refresh endpoint).
     * @return the parsed, validated claims.
     * @throws JwtVerificationException if the signature is invalid, the token is expired/not-yet-valid,
     *                                  the {@code iss}/{@code aud} do not match the configuration, or its
     *                                  {@code tokenType} does not match {@code expectedType}.
     */
    public ParsedToken verify(String token, TokenType expectedType) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secretBytes()))) {
                throw new JwtVerificationException("Invalid token signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            long skewSeconds = properties.clockSkew().getSeconds();
            Instant now = Instant.now();

            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.toInstant().plusSeconds(skewSeconds).isBefore(now)) {
                throw new JwtVerificationException("Token expired");
            }
            Date notBefore = claims.getNotBeforeTime();
            if (notBefore != null && notBefore.toInstant().minusSeconds(skewSeconds).isAfter(now)) {
                throw new JwtVerificationException("Token not yet valid");
            }
            // MF-1: iss + aud must match this backend's configuration, else the token is rejected.
            if (!properties.issuer().equals(claims.getIssuer())) {
                throw new JwtVerificationException("Unexpected issuer");
            }
            List<String> audiences = claims.getAudience();
            if (audiences == null || !audiences.contains(properties.audience())) {
                throw new JwtVerificationException("Unexpected audience");
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

    /** Signs a claims set with HS256, stamping iss/aud/iat/exp + the type (and roles/tier for access). */
    private String sign(UUID subject, TokenType type, List<String> roles, String trustTier, long ttlSeconds) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(subject.toString())
                    .issuer(properties.issuer())
                    .audience(properties.audience())
                    // A unique jti per token. WHY: without it, two tokens for the same subject+type minted
                    // in the same second carry identical claims → identical serialised JWT → identical hash,
                    // which collides on the unique refresh_token.token_hash index (e.g. signup then login of
                    // a staff account within one second). The jti makes every token unique (standard JWT
                    // hygiene); it is not validated on verify, so this is backward-compatible.
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now))
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
     * @param trustTier trust-tier hint from the token (never an authorization input — MF-2).
     */
    public record ParsedToken(UUID subject, List<String> roles, String trustTier) {
    }
}
