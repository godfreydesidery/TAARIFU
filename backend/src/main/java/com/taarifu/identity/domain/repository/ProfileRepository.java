package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Profile} (ARCHITECTURE.md §3.3, D15).
 *
 * <p>Responsibility: profile lookups and — critically — <b>identity dedup by blind index</b>.
 * {@link #findByIdHash(String)} resolves "is there already a profile for this (idType, idNo)?"
 * <b>without decrypting any ID</b> (D15, PRD §18) — the application computes the hash via
 * {@code CryptoPort.blindIndex(...)} and queries it. The encrypted {@code idNo} column is never
 * searched.</p>
 */
public interface ProfileRepository extends JpaRepository<Profile, Long> {

    /**
     * @param publicId the profile's public id.
     * @return the profile, or empty.
     */
    Optional<Profile> findByPublicId(UUID publicId);

    /**
     * @param user the owning account.
     * @return the account's 1:1 profile, or empty (used by {@code /me}, tier resolution).
     */
    Optional<Profile> findByUser(User user);

    /**
     * @param userPublicId the owning account's public id.
     * @return the profile for that account, or empty.
     */
    Optional<Profile> findByUser_PublicId(UUID userPublicId);

    /**
     * Dedup lookup by deterministic blind index — never decrypts (D15).
     *
     * @param idHash the {@code CryptoPort.blindIndex(idType + ":" + idNo)} value.
     * @return the existing profile with that identity, or empty (so signup/verification can block a
     *         duplicate identity).
     */
    Optional<Profile> findByIdHash(String idHash);

    /**
     * @param idHash the blind-index hash.
     * @return {@code true} if a profile already exists for that identity (fast dedup guard).
     */
    boolean existsByIdHash(String idHash);

    /**
     * Dedup guard for verification submit (D15): is this identity already bound to a <b>different</b>
     * account? Excluding the caller's own profile lets a citizen (re)submit their <b>own</b> ID without a
     * false {@code DUPLICATE_IDENTITY}, while still blocking a second account claiming the same ID.
     *
     * @param idHash the blind-index hash over {@code idType + ":" + idNo}.
     * @param user   the submitting caller's account (their own profile is excluded from the match).
     * @return {@code true} if another account already holds this identity (→ {@code DUPLICATE_IDENTITY}).
     */
    boolean existsByIdHashAndUserNot(String idHash, User user);
}
