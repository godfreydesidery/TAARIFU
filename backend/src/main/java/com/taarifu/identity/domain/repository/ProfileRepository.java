package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.Profile;
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
}
