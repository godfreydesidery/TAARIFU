package com.taarifu.identity.domain.repository;

import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.VerificationRequest;
import com.taarifu.identity.domain.model.enums.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link VerificationRequest} (ARCHITECTURE.md §3.3, §7).
 *
 * <p>Responsibility: drives the operator-assisted verification queue — list pending requests for
 * reviewers, fetch by public id. Backs the {@code IdentityVerificationProvider} degradation path
 * (EI-1/EI-2).</p>
 */
public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    /**
     * @param publicId the request's public id.
     * @return the request, or empty.
     */
    Optional<VerificationRequest> findByPublicId(UUID publicId);

    /**
     * @param status the review status to filter by (typically {@code PENDING}).
     * @return matching requests (the reviewer queue).
     */
    List<VerificationRequest> findByStatus(VerificationStatus status);

    /**
     * @param subject the account being verified.
     * @return that account's verification requests.
     */
    List<VerificationRequest> findBySubject(User subject);
}
