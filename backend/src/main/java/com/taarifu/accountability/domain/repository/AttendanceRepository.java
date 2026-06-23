package com.taarifu.accountability.domain.repository;

import com.taarifu.accountability.domain.model.Attendance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Attendance} (ARCHITECTURE.md §3.3).
 *
 * <p>Responsibility: powers the public US-6.1 attendance reads — per-session rows and a present/total
 * aggregate for a representative. Soft-deleted rows are excluded by the entity's {@code @SQLRestriction}.</p>
 */
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    /**
     * @param publicId the attendance row's public id.
     * @return the row, or empty.
     */
    Optional<Attendance> findByPublicId(UUID publicId);

    /**
     * @param representativeId the subject representative's public id.
     * @param sessionRef       the session reference.
     * @return the existing attendance row for that (rep, session), or empty (drives upsert-on-import).
     */
    Optional<Attendance> findByRepresentativeIdAndSessionRef(UUID representativeId, String sessionRef);

    /**
     * @param representativeId the subject representative's public id.
     * @param pageable         paging/sorting.
     * @return the representative's per-session attendance rows, paged.
     */
    Page<Attendance> findByRepresentativeId(UUID representativeId, Pageable pageable);

    /**
     * Computes the present/total attendance aggregate for a representative.
     *
     * <p>WHY computed (not stored): the aggregate is derived from the append-only rows so it cannot drift
     * from the underlying records (the same derive-don't-store discipline the rating aggregate uses).</p>
     *
     * @param representativeId the subject representative's public id.
     * @return a projection of {@code present} and {@code total} counts (both 0 when no rows).
     */
    @Query("""
            select count(a) as total, coalesce(sum(case when a.present = true then 1 else 0 end), 0) as present
            from Attendance a
            where a.representativeId = :representativeId
            """)
    AttendanceAggregate aggregateByRepresentativeId(@Param("representativeId") UUID representativeId);

    /**
     * Read projection for the attendance aggregate.
     */
    interface AttendanceAggregate {
        /** @return number of attended sessions. */
        long getPresent();

        /** @return total number of recorded sessions. */
        long getTotal();
    }
}
