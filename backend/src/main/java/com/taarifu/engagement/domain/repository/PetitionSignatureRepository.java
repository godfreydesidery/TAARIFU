package com.taarifu.engagement.domain.repository;

import com.taarifu.engagement.domain.model.PetitionSignature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data repository for {@link PetitionSignature} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: supports the one-person-one-signature check (UC-E03). The DB unique constraint is
 * the hard guarantee; this {@code exists} query is a fast pre-check that lets the service return a clean
 * {@link com.taarifu.common.error.ErrorCode#CONFLICT} envelope instead of surfacing a raw constraint
 * violation (the constraint remains the source of truth under race — the insert still fails-safe).</p>
 */
public interface PetitionSignatureRepository extends JpaRepository<PetitionSignature, Long> {

    /**
     * @param petitionPublicId the petition's public id.
     * @param signerProfileId  the candidate signer's identity {@code Profile} public id.
     * @return {@code true} if this signer has already signed this petition (one-per-person pre-check).
     */
    boolean existsByPetition_PublicIdAndSignerProfileId(UUID petitionPublicId, UUID signerProfileId);
}
