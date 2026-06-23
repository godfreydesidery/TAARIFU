package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted, <b>hashed</b> refresh token in the rotating-refresh scheme (ADR-0007,
 * ARCHITECTURE.md §6.1).
 *
 * <p>Responsibility: makes refresh tokens <b>revocable</b> (access tokens are not stored). Each token
 * belongs to a {@link #family}; refresh rotation issues a new token in the same family and marks the
 * old one used. <b>Reuse of an already-used token triggers family revocation</b> — a classic stolen-
 * token signal. This is the data layer only; the rotation/reuse-detection <i>logic</i> lands in the
 * auth increment (FOUNDATION-SCOPE.md §5), but the columns model it now.</p>
 *
 * <p>WHY only a <b>hash</b> of the token is stored ({@link #tokenHash}), never the token itself: a DB
 * compromise must not yield usable refresh tokens (PRD §18). Verification hashes the presented token
 * and compares — the raw token exists only in the client.</p>
 */
@Entity
@Table(name = "refresh_token", indexes = {
        @Index(name = "ux_refresh_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "ix_refresh_token_user", columnList = "user_id"),
        @Index(name = "ix_refresh_token_family", columnList = "family_id")
})
@SQLRestriction("deleted = false")
public class RefreshToken extends BaseEntity {

    /** The owning account (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 (or stronger) hash of the refresh token; unique. Never the raw token (PRD §18). */
    @Column(name = "token_hash", nullable = false, unique = true, length = 100)
    private String tokenHash;

    /**
     * Token-family identifier shared by a rotation chain. Reuse of a used token in a family revokes the
     * whole family (reuse-detection — ADR-0007).
     */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** Expiry instant (UTC). */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Whether this token has already been used to rotate (single-use). */
    @Column(name = "used", nullable = false)
    private boolean used = false;

    /** Whether this token has been explicitly revoked (e.g. family revocation, logout). */
    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    /** JPA requires a no-arg constructor; not for application use. */
    protected RefreshToken() {
    }

    /** @return the owning account. */
    public User getUser() {
        return user;
    }

    /** @return the token hash (never the raw token). */
    public String getTokenHash() {
        return tokenHash;
    }

    /** @return the rotation family id. */
    public UUID getFamilyId() {
        return familyId;
    }

    /** @return the expiry instant. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** @return whether the token has been used to rotate. */
    public boolean isUsed() {
        return used;
    }

    /** @return whether the token has been revoked. */
    public boolean isRevoked() {
        return revoked;
    }
}
