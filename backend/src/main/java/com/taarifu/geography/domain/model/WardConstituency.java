package com.taarifu.geography.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

/**
 * The <b>effective-dated</b> bridge mapping a Ward (Kata) to a Constituency (Jimbo)
 * (PRD §9.0, D14, EI-14, ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: records "this ward belonged to this constituency from {@code effectiveFrom} until
 * {@code effectiveTo}". Resolution always reads <b>the mapping effective at a given date D</b>, so a
 * boundary re-delimitation creates a <i>new</i> row (closing the old one) and <b>never rewrites
 * history</b> — a report filed last year still resolves to the constituency that existed then
 * (PRD §9.0).</p>
 *
 * <p>WHY effective dating rather than a plain ward→constituency FK: Tanzanian constituencies are
 * periodically re-delimited; a static FK would silently re-point historical records and corrupt the
 * civic record (the integrity failure this design exists to prevent — EI-14). {@code effectiveTo}
 * is nullable: {@code null} means "currently in effect".</p>
 *
 * <p>The "exactly one <i>current</i> mapping per ward" rule is enforced in the database by a
 * <b>partial unique index</b> on {@code ward_id WHERE effective_to IS NULL} (specified in the Flyway
 * migration — see the table list this slice hands the database engineer). JPA can express the FKs and
 * columns but not the partial index, so the DB owns that invariant.</p>
 */
@Entity
@Table(name = "ward_constituency", indexes = {
        @Index(name = "ix_ward_constituency_ward", columnList = "ward_id"),
        @Index(name = "ix_ward_constituency_constituency", columnList = "constituency_id"),
        @Index(name = "ix_ward_constituency_effective", columnList = "ward_id, effective_from, effective_to")
})
@SQLRestriction("deleted = false")
public class WardConstituency extends BaseEntity {

    /** The ward side of the mapping (FK to a {@link Location} of type {@code WARD}). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ward_id", nullable = false)
    private Location ward;

    /** The constituency side of the mapping (FK to {@link Constituency}). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "constituency_id", nullable = false)
    private Constituency constituency;

    /** Date this mapping took effect (inclusive). Never {@code null}. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /**
     * Date this mapping ceased to be in effect (exclusive), or {@code null} if it is the
     * <b>current</b> mapping. The partial unique index guarantees at most one {@code null}-{@code to}
     * row per ward.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** JPA requires a no-arg constructor; not for application use. */
    protected WardConstituency() {
    }

    /** @return the ward in this mapping. */
    public Location getWard() {
        return ward;
    }

    /** @return the constituency in this mapping. */
    public Constituency getConstituency() {
        return constituency;
    }

    /** @return the inclusive start date of this mapping. */
    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    /** @return the exclusive end date, or {@code null} if currently in effect. */
    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    /**
     * @param date the date to test.
     * @return {@code true} if this mapping is in effect on {@code date} (i.e. {@code effectiveFrom <=
     *         date} and ({@code effectiveTo} is {@code null} or {@code date < effectiveTo})).
     */
    public boolean isEffectiveOn(LocalDate date) {
        boolean started = !date.isBefore(effectiveFrom);
        boolean notEnded = effectiveTo == null || date.isBefore(effectiveTo);
        return started && notEnded;
    }
}
