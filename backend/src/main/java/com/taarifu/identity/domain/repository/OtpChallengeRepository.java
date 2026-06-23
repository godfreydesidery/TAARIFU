package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.OtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link OtpChallenge} (AUTH-DESIGN §3, §13.1).
 *
 * <p>Responsibility: resolves an OTP challenge by its public id on verify. Lookups never touch the
 * plaintext code (only the stored {@code code_hash} is ever compared), and the delivery target is never
 * logged (S-4).</p>
 */
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {

    /**
     * @param publicId the challenge's public id (returned to the client on request).
     * @return the challenge, or empty.
     */
    Optional<OtpChallenge> findByPublicId(UUID publicId);
}
