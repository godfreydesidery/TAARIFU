package com.taarifu.institutions.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.institutions.domain.model.enums.Legislature;
import com.taarifu.institutions.domain.model.enums.Mandate;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeType;
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

import java.time.LocalDate;
import java.util.UUID;

/**
 * An elected/appointed representative — MP (Mbunge), Councillor (Diwani), or ward/village executive
 * officer — and the heart of the institutions module (PRD §9.1, §22.6; D3, D17, UC-C01..C08).
 *
 * <p>Responsibility: links a verified citizen {@link #profileId} (the <b>same account</b>, §6.4) to the
 * civic geography and party/parliament they represent, with a dated lifecycle. It is the read model
 * behind "find my representatives", the representative profile, the directory, and search.</p>
 *
 * <h3>Cross-module reference discipline</h3>
 * <p>WHY {@link #profileId} is a bare {@code UUID}, not a FK to an identity entity: the institutions
 * module must <b>not</b> import {@code com.taarifu.identity} internals (module-boundary rule,
 * ARCHITECTURE.md §3.2). The link is the identity Profile's public id; the actual association (granting
 * the {@code REPRESENTATIVE} role to that account) is owned by identity/admin and wired at integration
 * time. Geography, by contrast, is an upstream foundation module whose {@link Constituency}/{@link
 * Location} entities this module legitimately FK-references (ARCHITECTURE.md §3.2 — foundation modules
 * may be referenced).</p>
 *
 * <h3>Mandate ⇄ geography integrity (the redesign of the anaemic legacy {@code Mp})</h3>
 * <p>The {@link #mandate} decides which geographic FK is populated:</p>
 * <ul>
 *   <li>{@link Mandate#CONSTITUENCY} → {@link #constituency} required, {@link #ward} null;</li>
 *   <li>{@link Mandate#COUNCILLOR_WARD} → {@link #ward} required, {@link #constituency} null;</li>
 *   <li>{@link Mandate#SPECIAL_SEATS}/{@link Mandate#NOMINATED} → <b>both null</b> (Viti Maalum /
 *       nominated MPs have no geographic seat — PRD §22.6).</li>
 * </ul>
 * <p>This shape is enforced defensively by a CHECK constraint in the migration, so a special-seats MP
 * with no constituency is a <i>valid, intentional</i> row, never a data error.</p>
 *
 * <h3>The load-bearing invariant: one SITTING constituency-MP per constituency</h3>
 * <p>At most one {@link RepresentativeStatus#SITTING} representative may hold a given constituency at a
 * time. JPA cannot express "unique constituency where status = SITTING", so the database owns it via a
 * <b>partial unique index</b> on {@code constituency_id WHERE status = 'SITTING' AND deleted = false}
 * (see the Flyway migration). {@code PENDING_VERIFICATION}/{@code FORMER} rows do not consume the slot,
 * so a successor can be staged before the incumbent is retired (UC-C08).</p>
 */
@Entity
@Table(name = "representative", indexes = {
        @Index(name = "ix_representative_profile", columnList = "profile_id"),
        @Index(name = "ix_representative_constituency", columnList = "constituency_id"),
        @Index(name = "ix_representative_ward", columnList = "ward_id"),
        @Index(name = "ix_representative_party", columnList = "party_id"),
        @Index(name = "ix_representative_parliament", columnList = "parliament_id"),
        @Index(name = "ix_representative_type", columnList = "type"),
        @Index(name = "ix_representative_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class Representative extends BaseEntity {

    /**
     * Public id of the identity Profile this representative <b>is</b> (1:1, same account — §6.4).
     * A cross-module reference by UUID only; never a FK into identity (ARCHITECTURE.md §3.2). Nullable
     * only to tolerate a "rep being onboarded" placeholder before the account is linked (PRD R2 mitigation).
     */
    @Column(name = "profile_id")
    private UUID profileId;

    /** The kind of leader (MP/Councillor/ward-exec) — drives find-my-rep fan-out and search facets. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private RepresentativeType type;

    /** How the seat is held — decides which geographic FK is populated (see class Javadoc). */
    @Enumerated(EnumType.STRING)
    @Column(name = "mandate", nullable = false, length = 24)
    private Mandate mandate;

    /**
     * The constituency (Jimbo) for a {@link Mandate#CONSTITUENCY} MP; {@code null} for ward-based,
     * special-seats, and nominated mandates. A real FK to the upstream geography {@link Constituency}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "constituency_id")
    private Constituency constituency;

    /**
     * The ward (Kata) for a {@link Mandate#COUNCILLOR_WARD} councillor / ward executive; {@code null}
     * for constituency, special-seats, and nominated mandates. A real FK to a geography {@link Location}
     * of type {@code WARD}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ward_id")
    private Location ward;

    /** The party (Chama) this representative is affiliated with; nullable (independents have none). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id")
    private PoliticalParty party;

    /** Which legislature the representative sits in (Union vs Zanzibar HoR) — D17. */
    @Enumerated(EnumType.STRING)
    @Column(name = "legislature", nullable = false, length = 24)
    private Legislature legislature = Legislature.UNION_PARLIAMENT;

    /** The parliament term/session the seat belongs to; nullable for councillors/ward-execs (no Bunge term). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parliament_id")
    private Parliament parliament;

    /** Optional additional parliamentary office held (e.g. Speaker, Minister); nullable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parliament_role_id")
    private ParliamentRole parliamentRole;

    /** Record lifecycle status (PENDING_VERIFICATION → SITTING → FORMER); see the invariant in class Javadoc. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private RepresentativeStatus status = RepresentativeStatus.PENDING_VERIFICATION;

    /** Date the representative was elected/took the seat (reference attribute); nullable while pending. */
    @Column(name = "elected_at")
    private LocalDate electedAt;

    /** Optional public biography (moderated free text); never PII beyond what the rep publishes (US-2.3). */
    @Column(name = "bio", length = 4000)
    private String bio;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Representative() {
    }

    /**
     * Factory for a new, empty representative — the only sanctioned construction path (constructor stays
     * {@code protected} so the integrity-critical entity is never instantiated outside the admin service).
     *
     * @return a fresh, unpopulated representative (caller then {@link #applyDetails}).
     */
    public static Representative create() {
        return new Representative();
    }

    /**
     * Applies an admin write of the representative's full state. WHY a single mutator taking already
     * <b>resolved</b> entities (not ids): the cross-module FK resolution and every integrity invariant
     * (mandate⇄geography coherence, one-SITTING-MP-per-constituency) are enforced by the admin service
     * <i>before</i> this is called; the entity simply records the validated state. Keeping the write in
     * one method preserves the entity's otherwise read-only public API (CLAUDE.md §8).
     *
     * @param profileId      linked identity Profile public id (by id only — never an identity import), or {@code null}.
     * @param type           representative kind.
     * @param mandate        how the seat is held.
     * @param constituency   resolved constituency, or {@code null} (must match the mandate rule).
     * @param ward           resolved ward, or {@code null} (must match the mandate rule).
     * @param party          resolved party, or {@code null} for an independent.
     * @param legislature    the legislature.
     * @param parliament     resolved parliament term, or {@code null}.
     * @param parliamentRole resolved additional office, or {@code null}.
     * @param status         lifecycle status.
     * @param electedAt      election/seating date, or {@code null}.
     * @param bio            public biography, or {@code null}.
     */
    public void applyDetails(UUID profileId, RepresentativeType type, Mandate mandate,
                             Constituency constituency, Location ward, PoliticalParty party,
                             Legislature legislature, Parliament parliament, ParliamentRole parliamentRole,
                             RepresentativeStatus status, LocalDate electedAt, String bio) {
        this.profileId = profileId;
        this.type = type;
        this.mandate = mandate;
        this.constituency = constituency;
        this.ward = ward;
        this.party = party;
        this.legislature = legislature;
        this.parliament = parliament;
        this.parliamentRole = parliamentRole;
        this.status = status;
        this.electedAt = electedAt;
        this.bio = bio;
    }

    /** @return the linked identity Profile public id, or {@code null} if not yet linked. */
    public UUID getProfileId() {
        return profileId;
    }

    /** @return the representative kind (MP/Councillor/ward-exec). */
    public RepresentativeType getType() {
        return type;
    }

    /** @return how the seat is held. */
    public Mandate getMandate() {
        return mandate;
    }

    /** @return the constituency for a constituency-mandate MP, or {@code null}. */
    public Constituency getConstituency() {
        return constituency;
    }

    /** @return the ward for a ward-mandate councillor/exec, or {@code null}. */
    public Location getWard() {
        return ward;
    }

    /** @return the affiliated party, or {@code null} for an independent. */
    public PoliticalParty getParty() {
        return party;
    }

    /** @return the legislature this representative sits in. */
    public Legislature getLegislature() {
        return legislature;
    }

    /** @return the parliament term, or {@code null}. */
    public Parliament getParliament() {
        return parliament;
    }

    /** @return the additional parliamentary office, or {@code null}. */
    public ParliamentRole getParliamentRole() {
        return parliamentRole;
    }

    /** @return the record lifecycle status. */
    public RepresentativeStatus getStatus() {
        return status;
    }

    /** @return the election/seating date, or {@code null}. */
    public LocalDate getElectedAt() {
        return electedAt;
    }

    /** @return the public biography, or {@code null}. */
    public String getBio() {
        return bio;
    }
}
