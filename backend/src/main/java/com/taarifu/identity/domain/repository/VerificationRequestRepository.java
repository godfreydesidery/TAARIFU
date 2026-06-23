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

    /**
     * The reviewer queue, narrowed to a status and type (e.g. PENDING ID requests for the Moderator
     * queue). Scope filtering (the caller's area scope) is applied in the service over this set, since
     * the subject's ward must be resolved through the identity model (kept out of the repository).
     *
     * @param status the review status (typically {@link VerificationStatus#PENDING}).
     * @param type   the verification kind (this increment lists {@code ID}).
     * @return matching requests, newest first by surrogate id.
     */
    List<VerificationRequest> findByStatusAndTypeOrderByIdDesc(VerificationStatus status,
                                                               com.taarifu.identity.domain.model.enums.VerificationType type);

    /**
     * Idempotency / no-duplicate-queue-entry guard (Flow 2): is there already an in-flight request of
     * this type for this subject? A second submit while one is {@code PENDING} returns the existing one.
     *
     * @param subject the account being verified.
     * @param type    the verification kind.
     * @param status  the status to match (typically {@link VerificationStatus#PENDING}).
     * @return the existing in-flight request, or empty.
     */
    Optional<VerificationRequest> findFirstBySubjectAndTypeAndStatus(
            User subject, com.taarifu.identity.domain.model.enums.VerificationType type,
            VerificationStatus status);
}
