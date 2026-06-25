package com.taarifu.communications.domain.repository;

import com.taarifu.communications.domain.model.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link DeviceToken} push-token registrations (ARCHITECTURE §3.3).
 *
 * <p>Responsibility: the persistence port for the device-token registry. The registration endpoint
 * upserts by raw token value; the push fan-out reads a recipient's live tokens; unregister (logout) and
 * invalid-token pruning find a token by value. Soft-deleted (unregistered/pruned) rows are excluded
 * automatically by the entity's {@code @SQLRestriction("deleted = false")}, so every query here returns
 * only live tokens.</p>
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    /**
     * Finds a live registration by its raw token value — the upsert, unregister, and prune lookup.
     *
     * @param token the opaque push registration token (secret; never logged).
     * @return the live registration, or empty if none/soft-deleted.
     */
    Optional<DeviceToken> findByToken(String token);

    /**
     * Lists a recipient profile's live device tokens — the push fan-out's recipient resolution and the
     * DSR-erasure handler's "tokens to revoke for this subject" lookup.
     *
     * @param profileId the recipient profile's public id.
     * @return the profile's live tokens (possibly empty → the dispatcher falls back to SMS, EI-5).
     */
    List<DeviceToken> findByProfileId(UUID profileId);
}
