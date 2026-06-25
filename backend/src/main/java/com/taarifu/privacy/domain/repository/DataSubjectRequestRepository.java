package com.taarifu.privacy.domain.repository;

import com.taarifu.privacy.domain.model.DataSubjectRequest;
import com.taarifu.privacy.domain.model.enums.DsrStatus;
import com.taarifu.privacy.domain.model.enums.DsrType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link DataSubjectRequest} (ARCHITECTURE.md §3.3; ADR-0016 §3).
 *
 * <p>Responsibility: resolve a request by public id (for the operator workflow + erasure handler),
 * detect an existing open request (to make a repeated self-request idempotent rather than spawn
 * duplicates), and list the operator queue. Soft-deleted rows are hidden by the entity's
 * {@code @SQLRestriction}.</p>
 */
public interface DataSubjectRequestRepository extends JpaRepository<DataSubjectRequest, Long> {

    /**
     * @param publicId the request's public id.
     * @return the request, or empty.
     */
    Optional<DataSubjectRequest> findByPublicId(UUID publicId);

    /**
     * A subject's still-open requests of a given type, in any non-terminal status — used to make a repeated
     * self-request idempotent (return the existing one rather than open a duplicate erasure).
     *
     * @param subjectPublicId the requesting account's public id.
     * @param type            ACCESS or ERASURE.
     * @param statuses        the non-terminal statuses considered "open".
     * @return matching open requests (never {@code null}).
     */
    List<DataSubjectRequest> findBySubjectPublicIdAndTypeAndStatusIn(UUID subjectPublicId, DsrType type,
                                                                     List<DsrStatus> statuses);

    /**
     * The operator queue: requests in the given statuses, oldest-due first.
     *
     * @param statuses the statuses to include (e.g. all open states).
     * @param pageable pagination + sort (the controller normalises).
     * @return the page of requests.
     */
    Page<DataSubjectRequest> findByStatusIn(List<DsrStatus> statuses, Pageable pageable);
}
