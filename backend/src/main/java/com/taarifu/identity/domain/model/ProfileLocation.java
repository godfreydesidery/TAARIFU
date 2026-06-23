package com.taarifu.identity.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.identity.domain.model.enums.AssociationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * A profile's link to a pinned place — <b>private PII</b> (PRD §9.0, §9.1, D12/D13).
 *
 * <p>Responsibility: a profile holds <b>many</b> locations (home/ancestral, residence, work…), each
 * typed by {@link AssociationType}. Two database-enforced singletons make this the spine of civic
 * integrity:</p>
 * <ul>
 *   <li>{@link #primary} — <b>exactly one</b> per profile: the default context (default feed, default
 *       report area, profile headline). Set at signup (D11).</li>
 *   <li>{@link #electoral} — <b>exactly one</b> per profile: the single location carrying <b>binding
 *       civic weight</b> (rate an MP, sign a constituency petition, vote in a binding poll — D13). It
 *       is <b>voter-ID-authoritative</b> and changing it is rate-limited (cooldown) and audited, to
 *       prevent double-influence across locations.</li>
 * </ul>
 *
 * <p>The "at most one primary" and "at most one electoral" rules are enforced in the database by two
 * <b>partial unique indexes</b> ({@code WHERE is_primary} / {@code WHERE is_electoral}) — JPA cannot
 * express partial uniqueness, so the Flyway migration owns those constraints (see the table list this
 * slice hands the database engineer). The change-cooldown <i>logic</i> lands in the auth/profile
 * increment; the columns exist now (FOUNDATION-SCOPE.md §5).</p>
 *
 * <p>WHY this entity is private and has <b>no public DTO</b> this increment: {@code ProfileLocation} is
 * PII and must never appear in any public search index or response (PRD §9.0, §22.1) — building a DTO
 * would invite leakage. Reads are internal only.</p>
 *
 * <p>WHY real FKs to geography (cross-module): FOUNDATION-SCOPE.md §5 mandates real FKs to {@code
 * Location}/{@code Constituency} (no loose {@code Long} ids — fixes legacy, PRD §6.3). This is a
 * deliberate persistence-level reference to the geography model; the closed module boundary still holds
 * at the application layer (identity services call geography's public API, not its repositories).</p>
 */
@Entity
@Table(name = "profile_location", indexes = {
        @Index(name = "ix_profile_location_profile", columnList = "profile_id"),
        @Index(name = "ix_profile_location_ward", columnList = "ward_id"),
        @Index(name = "ix_profile_location_constituency", columnList = "constituency_id")
})
@SQLRestriction("deleted = false")
public class ProfileLocation extends BaseEntity {

    /** The owning profile (FK). Many locations per profile. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private Profile profile;

    /**
     * The pinned ward (FK to a geography {@link Location} of type {@code WARD}). Minimum pin
     * granularity is Ward (PRD §9.0); finer levels (village/hamlet) may be added later. Real FK.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ward_id", nullable = false)
    private Location ward;

    /**
     * The derived constituency for this pin (FK to {@link Constituency}), resolved via the
     * effective-dated bridge at pin time. Stored for fast scoping; {@code null} until resolved.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "constituency_id")
    private Constituency constituency;

    /** How the profile relates to this place. */
    @Enumerated(EnumType.STRING)
    @Column(name = "association_type", nullable = false, length = 24)
    private AssociationType associationType = AssociationType.RESIDENCE;

    /**
     * Whether this is the profile's single primary (default-context) location. The partial unique index
     * guarantees at most one {@code true} per profile (PRD §9.0, D12).
     */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    /**
     * Whether this is the profile's single electoral (binding-civic-weight) location. The partial unique
     * index guarantees at most one {@code true} per profile; voter-ID-authoritative, change-cooldown
     * (D13).
     */
    @Column(name = "is_electoral", nullable = false)
    private boolean electoral = false;

    /**
     * When the electoral flag was last changed (UTC), to enforce the change cooldown in the auth
     * increment (D13). {@code null} until first set.
     */
    @Column(name = "electoral_changed_at")
    private Instant electoralChangedAt;

    /** JPA requires a no-arg constructor; not for application use. */
    protected ProfileLocation() {
    }

    /**
     * Pins a place to a profile. The "at most one primary" rule is the DB partial-unique index's job
     * (the caller must clear any prior primary first); this factory only constructs the row.
     *
     * @param profile         the owning profile.
     * @param ward            the pinned ward (geography {@code Location} of type WARD; min granularity).
     * @param constituency    the derived constituency at pin time, or {@code null} if unresolved.
     * @param associationType how the profile relates to the place.
     * @param primary         whether this is the single primary (default-context) location.
     * @return the populated, transient location row (PRIVATE PII — never in a public DTO).
     */
    public static ProfileLocation pin(Profile profile, Location ward, Constituency constituency,
                                      AssociationType associationType, boolean primary) {
        ProfileLocation pl = new ProfileLocation();
        pl.profile = profile;
        pl.ward = ward;
        pl.constituency = constituency;
        pl.associationType = associationType;
        pl.primary = primary;
        return pl;
    }

    /** Clears the primary flag (when re-assigning the single primary to a new location, D12). */
    public void clearPrimary() {
        this.primary = false;
    }

    /** Sets this as the single primary (default-context) location; the caller demotes any prior primary (D12). */
    public void markPrimary() {
        this.primary = true;
    }

    /**
     * Marks this as the single electoral (binding-civic-weight) location and stamps the change instant
     * for the cooldown (D13). The caller demotes any prior electoral in the same transaction (the DB
     * partial-unique index {@code ux_profile_location_one_electoral} is the hard backstop against a race).
     *
     * @param now the change instant (UTC, from the injected clock) used to enforce the manual-change
     *            cooldown on the next attempt.
     */
    public void markElectoral(Instant now) {
        this.electoral = true;
        this.electoralChangedAt = now;
    }

    /** Clears the electoral flag (when re-assigning the single electoral to another location, D13). */
    public void clearElectoral() {
        this.electoral = false;
    }

    /** @return the owning profile. */
    public Profile getProfile() {
        return profile;
    }

    /** @return the pinned ward location. */
    public Location getWard() {
        return ward;
    }

    /** @return the derived constituency, or {@code null} if not yet resolved. */
    public Constituency getConstituency() {
        return constituency;
    }

    /** @return how the profile relates to this place. */
    public AssociationType getAssociationType() {
        return associationType;
    }

    /** @return whether this is the single primary location. */
    public boolean isPrimary() {
        return primary;
    }

    /** @return whether this is the single electoral location. */
    public boolean isElectoral() {
        return electoral;
    }

    /** @return when the electoral flag was last changed, or {@code null}. */
    public Instant getElectoralChangedAt() {
        return electoralChangedAt;
    }
}
