package com.taarifu.responders.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

/**
 * A responder <b>organisation</b> — the legal/operational body behind one or more responder
 * capabilities (PRD §24.1, §24.5, D20/D21).
 *
 * <p>Responsibility: holds the identity, classification, contacts, status and verification of a
 * government agency, parastatal, utility, bank, telecom, civic org or private company that handles
 * citizen reports. It generalises the legacy "Organisation"/"Area Official employer": a government
 * Area Official is simply staff of an {@link OrganisationType#GOVERNMENT_AGENCY} organisation (§24.1).
 * The actual handling capability (sectors, coverage, SLA) lives on the related {@link Responder}.</p>
 *
 * <p>WHY {@code verified} is a first-class column (not just a status): §24.4 requires a provider to be
 * <b>verified before going live</b>, with a verified badge, and impersonation guarded. Verification is
 * a Moderator/Admin decision separate from the operational {@link OrganisationStatus} — a body can be
 * {@code ACTIVE} but not yet {@code verified} (so it must not appear publicly). The public directory
 * only ever lists organisations that are both active and verified.</p>
 *
 * <p>WHY {@code ownerUserPublicId} is a loose {@code UUID} and not an FK: it references the
 * {@code identity} module's user; per the module-boundary rules this module references other modules by
 * id and resolves them through their public API, never via a cross-module FK into identity's tables
 * (ARCHITECTURE.md §3.2). The geography/category linkages on {@link Responder} are likewise by id.</p>
 */
@Entity
@Table(name = "responder_organisation", indexes = {
        @Index(name = "ix_responder_org_type", columnList = "type"),
        @Index(name = "ix_responder_org_status", columnList = "status")
})
@SQLRestriction("deleted = false")
public class Organisation extends BaseEntity {

    /** Display name of the organisation (e.g. "TANESCO", "CRDB Bank"). */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** What kind of body this is — drives routing taxonomy and phased onboarding (D20). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private OrganisationType type;

    /** Operational lifecycle state (PENDING/ACTIVE/SUSPENDED/DISABLED). Distinct from {@code verified}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrganisationStatus status = OrganisationStatus.PENDING;

    /**
     * Whether a Moderator/Admin has verified this organisation (PRD §24.4). Only verified <b>and</b>
     * active organisations are publicly listed and may receive routed reports; impersonation guarded.
     */
    @Column(name = "verified", nullable = false)
    private boolean verified = false;

    /** Public contact phone (directory display; not PII of a citizen). Optional. */
    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    /** Public contact email (directory display). Optional. */
    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    /** Public website URL (directory display). Optional. */
    @Column(name = "website_url", length = 300)
    private String websiteUrl;

    /**
     * The {@code identity} user who administers this organisation's workspace, referenced <b>by id</b>
     * (no cross-module FK — ARCHITECTURE.md §3.2). Resolved via identity's public API when needed.
     * Optional until an admin is bound. // TODO(wiring): resolve/validate against identity module.
     */
    @Column(name = "owner_user_public_id")
    private java.util.UUID ownerUserPublicId;

    /** JPA requires a no-arg constructor; not for application use. */
    protected Organisation() {
    }

    /**
     * Creates a new, unverified, PENDING organisation (the state every org starts in before
     * Moderator/Admin verification and activation, §24.4).
     *
     * @param name display name.
     * @param type the organisation kind (drives routing/onboarding).
     * @return a transient organisation ready to persist.
     */
    public static Organisation create(String name, OrganisationType type) {
        Organisation org = new Organisation();
        org.name = name;
        org.type = type;
        org.status = OrganisationStatus.PENDING;
        org.verified = false;
        return org;
    }

    /** Sets the public contact details (directory display). */
    public void setContacts(String contactPhone, String contactEmail, String websiteUrl) {
        this.contactPhone = contactPhone;
        this.contactEmail = contactEmail;
        this.websiteUrl = websiteUrl;
    }

    /** Renames the organisation. */
    public void rename(String name) {
        this.name = name;
    }

    /** Reclassifies the organisation type (e.g. corrects a mis-seeded kind). */
    public void changeType(OrganisationType type) {
        this.type = type;
    }

    /** Updates the operational status (activation/suspension, §24.4). */
    public void changeStatus(OrganisationStatus status) {
        this.status = status;
    }

    /**
     * Marks the organisation verified or not (Moderator/Admin action, §24.4). A verified org may be
     * listed publicly once also active; un-verifying immediately removes it from the public directory.
     *
     * @param verified the new verification flag.
     */
    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    /** Binds the administering identity user (by id). // TODO(wiring): validate via identity API. */
    public void setOwnerUserPublicId(java.util.UUID ownerUserPublicId) {
        this.ownerUserPublicId = ownerUserPublicId;
    }

    /**
     * @return {@code true} if this organisation may appear in the public directory and receive routed
     *         reports — it must be {@link OrganisationStatus#ACTIVE} <b>and</b> {@code verified}
     *         (PRD §24.4). Centralising the rule here keeps "publicly listable" defined in one place.
     */
    public boolean isPubliclyListable() {
        return status == OrganisationStatus.ACTIVE && verified;
    }

    /** @return the display name. */
    public String getName() {
        return name;
    }

    /** @return the organisation kind. */
    public OrganisationType getType() {
        return type;
    }

    /** @return the operational status. */
    public OrganisationStatus getStatus() {
        return status;
    }

    /** @return whether the organisation is verified. */
    public boolean isVerified() {
        return verified;
    }

    /** @return public contact phone, or {@code null}. */
    public String getContactPhone() {
        return contactPhone;
    }

    /** @return public contact email, or {@code null}. */
    public String getContactEmail() {
        return contactEmail;
    }

    /** @return public website URL, or {@code null}. */
    public String getWebsiteUrl() {
        return websiteUrl;
    }

    /** @return the administering identity user's public id, or {@code null}. */
    public java.util.UUID getOwnerUserPublicId() {
        return ownerUserPublicId;
    }
}
