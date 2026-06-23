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
}
