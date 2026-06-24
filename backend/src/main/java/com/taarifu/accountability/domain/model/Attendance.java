package com.taarifu.accountability.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A representative's attendance record for one parliamentary session/sitting (PRD §10 Epic M6, US-6.1;
 * EI-11).
 *
 * <p>Responsibility: one row per (representative, session) capturing whether the representative was
 * present. Citizens read aggregate attendance to judge diligence. <b>Curated authorship (D-Q4):</b>
 * created by an authorised author / {@code ROLE_ADMIN} from official records, with provenance; citizens
 * read-only.</p>
 *
 * <p>WHY {@code representativeId} is an opaque {@link UUID}: the representative lives in the
 * <b>institutions</b> module which this module must not import — it is referenced by public id, and its
 * existence is confirmed via institutions' published {@code RepresentativeQueryApi.exists} port in
 * {@code CurationService} before any attendance row is persisted (ADR-0013).</p>
 *
 * <p>WHY the {@code (representative_id, session_ref)} uniqueness: a representative is either present or
 * absent for a given sitting — exactly one authoritative attendance row per session, so aggregates
 * (present/total) are correct and re-imports update rather than duplicate.</p>
 */
@Entity
@Table(name = "attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_attendance_rep_session",
                columnNames = {"representative_id", "session_ref"}),
        indexes = {
                @Index(name = "ix_attendance_representative", columnList = "representative_id")
        })
@SQLRestriction("deleted = false")
public class Attendance extends BaseEntity {

    /**
     * Public id of the representative (institutions module — referenced by id only, never an FK; existence
     * validated via {@code RepresentativeQueryApi.exists} in {@code CurationService} before persistence).
     */
    @Column(name = "representative_id", nullable = false)
    private UUID representativeId;

    /** Stable reference for the session/sitting this record covers (e.g. {@code "2026-06-18"} or a code). */
    @Column(name = "session_ref", nullable = false, length = 120)
    private String sessionRef;

    /** {@code true} if the representative attended this session. */
    @Column(name = "present", nullable = false)
    private boolean present;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Attendance() {
    }

    /**
     * Creates an attendance record.
     *
     * @param representativeId public id of the subject representative (institutions module).
     * @param sessionRef       the session/sitting reference (required).
     * @param present          whether the representative attended.
     * @return the populated, transient entity.
     */
    public static Attendance create(UUID representativeId, String sessionRef, boolean present) {
        Attendance a = new Attendance();
        a.representativeId = representativeId;
        a.sessionRef = sessionRef;
        a.present = present;
        return a;
    }

    /** @return the subject representative's public id (institutions module). */
    public UUID getRepresentativeId() {
        return representativeId;
    }

    /** @return the session/sitting reference. */
    public String getSessionRef() {
        return sessionRef;
    }

    /** @return {@code true} if the representative attended. */
    public boolean isPresent() {
        return present;
    }
}
