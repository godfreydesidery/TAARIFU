package com.taarifu.institutions.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.institutions.domain.model.enums.Legislature;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

/**
 * A parliamentary term/session of a legislature (Bunge term) — reference-directory entity
 * (PRD §9.1, §22.6).
 *
 * <p>Responsibility: anchors representatives to a specific term (e.g. "12th Parliament, 2020–2025") so a
 * representative's record is dated to the legislature they served in. A {@link Representative} FKs the
 * parliament/term they hold a seat in; accountability data (Phase 2) hangs off the same dating.</p>
 *
 * <p>WHY {@code isCurrent} is a stored flag (not derived purely from dates): the "current" term is an
 * administrative declaration that can lag or lead the nominal end date (dissolution, by-elections,
 * extended sessions); a single explicitly-current term is the authoritative anchor for "the sitting
 * Parliament" reads. The "at most one current term per legislature" rule is owned by the database via a
 * <b>partial unique index</b> (JPA cannot express it) — see the Flyway migration.</p>
 *
 * <p>{@link #legislature} separates the Union Parliament from the Zanzibar House of Representatives so
 * the two never collide in directory reads or the current-term invariant (D17).</p>
 */
@Entity
@Table(name = "parliament", indexes = {
        @Index(name = "ix_parliament_legislature", columnList = "legislature"),
        @Index(name = "ix_parliament_current", columnList = "is_current")
})
@SQLRestriction("deleted = false")
public class Parliament extends BaseEntity {

    /**
     * The term/session number of this parliament (e.g. {@code 12} for the 12th Parliament). Combined
     * with {@link #legislature} this is the human-meaningful identity of the term.
     */
    @Column(name = "term_number", nullable = false)
    private Integer termNumber;

    /** Display name of the term (e.g. "12th Parliament of Tanzania"). */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Which legislature this term belongs to (Union vs Zanzibar HoR). */
    @Enumerated(EnumType.STRING)
    @Column(name = "legislature", nullable = false, length = 24)
    private Legislature legislature = Legislature.UNION_PARLIAMENT;

    /** Date the term commenced (inclusive). */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Date the term ended (exclusive), or {@code null} if the term is ongoing. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Whether this is the currently-sitting term of its legislature. At most one row per legislature
     * may be {@code true} (DB partial unique index — see migration).
     */
    @Column(name = "is_current", nullable = false)
    private boolean current = false;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Parliament() {
    }

    /**
     * Factory for a new, empty parliament term — the only sanctioned construction path (constructor
     * stays {@code protected}).
     *
     * @return a fresh, unpopulated parliament term.
     */
    public static Parliament create() {
        return new Parliament();
    }

    /**
     * Applies an admin write. WHY a single mutator: the term is written only by the admin service in one
     * coherent call; the single-current-per-legislature invariant is enforced by that service (which
     * clears the prior current term) and the DB partial-unique index, not by this setter.
     *
     * @param termNumber  term/session number.
     * @param name        display name.
     * @param legislature legislature.
     * @param startDate   inclusive start date.
     * @param endDate     exclusive end date, or {@code null}.
     * @param current     whether this term is the currently-sitting one.
     */
    public void applyDetails(Integer termNumber, String name, Legislature legislature,
                             LocalDate startDate, LocalDate endDate, boolean current) {
        this.termNumber = termNumber;
        this.name = name;
        this.legislature = legislature;
        this.startDate = startDate;
        this.endDate = endDate;
        this.current = current;
    }

    /**
     * Clears the current-term flag (used by the admin service when another term of the same legislature
     * is made current).
     */
    public void clearCurrent() {
        this.current = false;
    }

    /** @return the term/session number. */
    public Integer getTermNumber() {
        return termNumber;
    }

    /** @return the term display name. */
    public String getName() {
        return name;
    }

    /** @return the legislature this term belongs to. */
    public Legislature getLegislature() {
        return legislature;
    }

    /** @return the inclusive start date of the term. */
    public LocalDate getStartDate() {
        return startDate;
    }

    /** @return the exclusive end date, or {@code null} if ongoing. */
    public LocalDate getEndDate() {
        return endDate;
    }

    /** @return {@code true} if this is the currently-sitting term of its legislature. */
    public boolean isCurrent() {
        return current;
    }
}
