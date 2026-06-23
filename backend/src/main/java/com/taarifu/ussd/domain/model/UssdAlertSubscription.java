package com.taarifu.ussd.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A feature-phone citizen's request, made over USSD, to receive SMS alerts for their registered area
 * (PRD §14 menu item 3 "my-area alerts", EI-3/EI-4).
 *
 * <p>Responsibility: record the <b>intent</b> "this MSISDN's account wants area alerts for this ward" so a
 * USSD user without an app/feed can still be reached by area announcements over SMS. It is keyed by the
 * MSISDN-linked account ({@link #userPublicId}) + the {@link #wardId}.</p>
 *
 * <p>WHY this lives in the ussd module and not directly as a communications {@code Subscription}: the
 * isolation rule forbids this module from writing communications' tables, and communications'
 * {@code Subscription} is keyed by a profile id on an authenticated path — neither available cleanly on the
 * USSD webhook. So the intent is captured locally and (once a published communications command port exists)
 * forwarded to register the real follow/notification preference — see {@code // TODO(wiring)} in
 * {@code UssdAlertService} and CENTRAL INTEGRATION NEEDS. Both ids are bare {@code UUID}s (identity account,
 * geography ward), never FKs (ARCHITECTURE §3.2).</p>
 *
 * <p>WHY uniqueness is scoped to live rows: a citizen subscribes a given area at most once; unsubscribe is a
 * soft-delete so the history stays auditable (PRD §9) and a re-subscribe inserts a fresh row. JPA cannot
 * express partial uniqueness, so the Flyway migration owns the {@code WHERE deleted = false} partial unique
 * index; the table-level constraint here documents the intent.</p>
 */
@Entity
@Table(name = "ussd_alert_subscription",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_ussd_alert_sub",
                        columnNames = {"user_public_id", "ward_id"})
        },
        indexes = {
                @Index(name = "ix_ussd_alert_user", columnList = "user_public_id"),
                @Index(name = "ix_ussd_alert_ward", columnList = "ward_id")
        })
@SQLRestriction("deleted = false")
public class UssdAlertSubscription extends BaseEntity {

    /** Public id of the MSISDN-linked account (identity). Bare ref, never a FK. */
    @Column(name = "user_public_id", nullable = false)
    private UUID userPublicId;

    /** Public id of the subscribed ward (geography), min pin granularity. Bare ref, never a FK. */
    @Column(name = "ward_id", nullable = false)
    private UUID wardId;

    /**
     * Whether the intent has been forwarded to communications. {@code false} until a published
     * communications command port exists to register the real area-alert preference ({@code // TODO(wiring)}).
     */
    @Column(name = "forwarded", nullable = false)
    private boolean forwarded = false;

    /** JPA requires a no-arg constructor; not for application use. */
    protected UssdAlertSubscription() {
    }

    /**
     * Records an area-alert subscription intent.
     *
     * @param userPublicId the MSISDN-linked account public id.
     * @param wardId       the subscribed ward public id.
     * @return the populated, transient subscription (not yet forwarded).
     */
    public static UssdAlertSubscription of(UUID userPublicId, UUID wardId) {
        UssdAlertSubscription a = new UssdAlertSubscription();
        a.userPublicId = userPublicId;
        a.wardId = wardId;
        return a;
    }

    /** Marks the intent as forwarded to communications (set when the wiring lands). */
    public void markForwarded() {
        this.forwarded = true;
    }

    /** @return the subscribing account public id. */
    public UUID getUserPublicId() {
        return userPublicId;
    }

    /** @return the subscribed ward public id. */
    public UUID getWardId() {
        return wardId;
    }

    /** @return whether the intent has been forwarded to communications. */
    public boolean isForwarded() {
        return forwarded;
    }
}
