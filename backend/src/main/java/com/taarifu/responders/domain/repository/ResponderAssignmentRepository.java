package com.taarifu.responders.domain.repository;

import com.taarifu.responders.domain.model.ResponderAssignment;
import com.taarifu.responders.domain.model.enums.AssignmentRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ResponderAssignment} (ARCHITECTURE.md §3.3, PRD §24.3).
 *
 * <p>Responsibility: read/write the owner+collaborator assignments of a report. The report is
 * referenced by id only (reporting built in parallel). The {@code findOwner}/{@code existsOwner}
 * methods back the application-layer single-owner guard that mirrors the DB partial-unique index
 * (defense in depth — the DB owns the invariant, the service gives a typed CONFLICT before the
 * constraint fires).</p>
 */
public interface ResponderAssignmentRepository extends JpaRepository<ResponderAssignment, Long> {

    /**
     * @param publicId the assignment's public id.
     * @return the assignment, or empty if none/soft-deleted.
     */
    Optional<ResponderAssignment> findByPublicId(UUID publicId);

    /**
     * Lists all live assignments for a report (owner + collaborators), for aggregation/display.
     *
     * @param reportId the report id (loose reference).
     * @return the report's assignments (possibly empty).
     */
    List<ResponderAssignment> findByReportId(UUID reportId);

    /**
     * Finds the report's single OWNER assignment, if any (PRD §24.3).
     *
     * @param reportId the report id.
     * @return the owner assignment, or empty if the report has no owner yet.
     */
    @Query("""
            SELECT a FROM ResponderAssignment a
            WHERE a.reportId = :reportId AND a.role = :ownerRole
            """)
    Optional<ResponderAssignment> findOwner(@Param("reportId") UUID reportId,
                                            @Param("ownerRole") AssignmentRole ownerRole);

    /**
     * @param reportId          the report id.
     * @param responderPublicId the responder's public id.
     * @return an existing live assignment of this responder to this report, if any (duplicate guard).
     */
    @Query("""
            SELECT a FROM ResponderAssignment a
            WHERE a.reportId = :reportId AND a.responder.publicId = :responderPublicId
            """)
    Optional<ResponderAssignment> findByReportAndResponder(@Param("reportId") UUID reportId,
                                                           @Param("responderPublicId") UUID responderPublicId);
}
