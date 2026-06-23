package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link ProfileLocation} — <b>private PII</b> (PRD §9.0, §22.1).
 *
 * <p>Responsibility: internal reads of a profile's locations and its two singletons. There is <b>no
 * public DTO/endpoint</b> for these by design — {@code ProfileLocation} must never be exposed publicly
 * or indexed (PRD §9.0, §22.1). The {@code findPrimary}/{@code findElectoral} lookups support the
 * action-scoping rules (D12/D13): default context vs binding civic weight.</p>
 */
public interface ProfileLocationRepository extends JpaRepository<ProfileLocation, Long> {

    /**
     * @param profile the owning profile.
     * @return all of the profile's locations.
     */
    List<ProfileLocation> findByProfile(Profile profile);

    /**
     * @param profile the owning profile.
     * @return the single primary (default-context) location, or empty (D12).
     */
    Optional<ProfileLocation> findByProfileAndPrimaryTrue(Profile profile);

    /**
     * @param profile the owning profile.
     * @return the single electoral (binding-civic-weight) location, or empty (D13).
     */
    Optional<ProfileLocation> findByProfileAndElectoralTrue(Profile profile);

    /**
     * @param profile  the owning profile.
     * @param publicId a location's public id.
     * @return the matching location if it belongs to this profile (ownership guard), or empty.
     */
    Optional<ProfileLocation> findByProfileAndPublicId(Profile profile, java.util.UUID publicId);

    /**
     * @param profile the owning profile.
     * @return the number of (non-deleted) locations the profile holds — used by the delete guard to
     *         refuse removing the last location of a T2+ user (it would break the ≥1-pin T2 predicate).
     */
    long countByProfile(Profile profile);
}
