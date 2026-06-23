package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link RefreshToken} (ADR-0007, ARCHITECTURE.md §6.1).
 *
 * <p>Responsibility: supports refresh-token rotation and revocation. {@link #findByTokenHash(String)}
 * verifies a presented token by its hash (never the raw token); {@link #findByFamilyId(UUID)} backs
 * <b>family revocation</b> on reuse-detection — revoking the whole chain when a used token reappears
 * (ADR-0007). Rotation logic lands in the auth increment.</p>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * @param tokenHash the hash of the presented refresh token.
     * @return the matching stored token, or empty.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * @param familyId the rotation-family id.
     * @return all tokens in the family (for family revocation on reuse-detection).
     */
    List<RefreshToken> findByFamilyId(UUID familyId);
}
