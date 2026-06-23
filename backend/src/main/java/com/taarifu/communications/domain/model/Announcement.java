package com.taarifu.communications.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.model.enums.Channel;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A geo-targeted civic announcement published by an official, representative, or organisation
 * (PRD §9.1, §12 state machine, M4 / US-4.1, UC-G01/G02/G03).
 *
 * <p>Responsibility: the authored message that fans out to matching subscribers' feeds and queues
 * channel notifications. It carries bilingual body text (Swahili default + optional English — ADR-0010),
 * an {@link #audienceScope() audience scope} (geo area refs in {@link #audienceAreaIds} plus optional
 * role/category refs), the chosen {@link #channels}, scheduling window ({@link #publishAt}/{@link
 * #expireAt}), and a lifecycle {@link #status} (PRD §12: {@code DRAFT → SCHEDULED → PUBLISHED →
 * EXPIRED}).</p>
 *
 * <p>WHY cross-module references are stored as bare {@code UUID}s (not FKs): the author profile, the
 * audience areas (geography), and any role/category targets are owned by <b>other modules</b>. Per the
 * modular-monolith boundary (ARCHITECTURE §3.2) and the parallel-build isolation rule, this module
 * references them by public id only and resolves them through the owning module's public API — it never
 * FK-joins another module's tables. The ids are validated by the owning module at write time.</p>
 *
 * <p>WHY a {@code moderationHeld} flag distinct from {@link AnnouncementStatus}: a <b>new or untrusted
 * author</b>'s announcement is held for moderation before it may publish (US-4.1, UC-G03). The hold is a
 * separate axis from the publish lifecycle so a held draft is unambiguous ({@code status=DRAFT} +
 * {@code moderationHeld=true}) and clearing the hold never silently changes the lifecycle state. The
 * actual moderation review lives in the {@code moderation} module; this flag is the gate this module
 * checks before allowing {@code PUBLISHED} (TODO(wiring): subscribe to the moderation decision event).</p>
 */
@Entity
@Table(name = "announcement", indexes = {
        @Index(name = "ix_announcement_author", columnList = "author_profile_id"),
        @Index(name = "ix_announcement_status", columnList = "status"),
        // The hot feed query: live, non-expired announcements ordered by publish time.
        @Index(name = "ix_announcement_status_publish", columnList = "status, publish_at")
})
@SQLRestriction("deleted = false")
public class Announcement extends BaseEntity {

    /**
     * Public id of the authoring profile (official/representative/org). A bare {@code UUID} reference to
     * the {@code identity} module — resolved/authorised there, never FK-joined here (ARCHITECTURE §3.2).
     */
    @Column(name = "author_profile_id", nullable = false)
    private UUID authorProfileId;

    /** Headline. Required; kept short for SMS/feed economy (PRD §15 data budget). */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Body in Swahili (the platform default locale, ADR-0010). Required. */
    @Column(name = "body_sw", nullable = false, length = 4000)
    private String bodySw;

    /** Body in English (secondary locale), or {@code null} if the author supplied Swahili only. */
    @Column(name = "body_en", length = 4000)
    private String bodyEn;

    /**
     * Public id of the issue category this announcement is tagged with (for category-follow targeting),
     * or {@code null}. Bare {@code UUID} reference to the {@code reporting} module's IssueCategory
     * (TODO(wiring): validate against the category directory once that module is merged).
     */
    @Column(name = "category_id")
    private UUID categoryId;

    /**
     * Optional role name the audience is narrowed to (e.g. only {@code REPRESENTATIVE}s), or {@code null}
     * for "any role". Stored as the role's stable name string (the role catalogue lives in {@code
     * identity}); not a FK.
     */
    @Column(name = "audience_role", length = 32)
    private String audienceRole;

    /** Lifecycle state (PRD §12). New announcements start in {@code DRAFT}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AnnouncementStatus status = AnnouncementStatus.DRAFT;

    /**
     * Whether this announcement is held for moderation before it may publish — set for new/untrusted
     * authors (US-4.1, UC-G03). {@code true} blocks the {@code →PUBLISHED} transition until cleared.
     */
    @Column(name = "moderation_held", nullable = false)
    private boolean moderationHeld = false;

    /** When the announcement should go (or went) live (UTC); {@code null} means "publish immediately". */
    @Column(name = "publish_at")
    private Instant publishAt;

    /** When the announcement stops being shown in feeds (UTC); {@code null} means "no expiry". */
    @Column(name = "expire_at")
    private Instant expireAt;

    /**
     * The geo area public ids this announcement targets (the audience-scope geo dimension). Bare
     * {@code UUID}s referencing geography {@code Location}s (e.g. Wards) — never FKs (ARCHITECTURE §3.2).
     * Held in a child table; a {@code LinkedHashSet} keeps insertion order stable and rejects dupes.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "announcement_audience_area",
            joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "area_id", nullable = false)
    private Set<UUID> audienceAreaIds = new LinkedHashSet<>();

    /**
     * The channels this announcement is delivered over (PRD §9.1 {@code channels[] {FEED,PUSH,SMS}}).
     * {@code FEED} is the durable default; {@code SMS} is opt-in per its cost (PRD §13 "SMS optional").
     * Held in a child table as enum names.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "announcement_channel",
            joinColumns = @JoinColumn(name = "announcement_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16)
    private Set<Channel> channels = new LinkedHashSet<>();

    /**
     * Object-store keys for attachments (signed-URL fetched, virus-scanned on upload — EI-8). Bare
     * string refs, never inline blobs; held in a child table.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "announcement_attachment",
            joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "attachment_ref", nullable = false, length = 512)
    private Set<String> attachmentRefs = new LinkedHashSet<>();

    /** JPA requires a no-arg constructor; not for application use. */
    protected Announcement() {
    }

    /**
     * Drafts a new announcement. The author and required text are set; audience/channels/schedule are
     * populated via the mutators below before publish. Starts in {@code DRAFT}; the caller sets
     * {@link #hold()} when the author is new/untrusted (US-4.1).
     *
     * @param authorProfileId the authoring profile's public id.
     * @param title           the headline.
     * @param bodySw          the Swahili body (required).
     * @param bodyEn          the English body, or {@code null}.
     * @return the populated, transient announcement in {@code DRAFT}.
     */
    public static Announcement draft(UUID authorProfileId, String title, String bodySw, String bodyEn) {
        Announcement a = new Announcement();
        a.authorProfileId = authorProfileId;
        a.title = title;
        a.bodySw = bodySw;
        a.bodyEn = bodyEn;
        a.status = AnnouncementStatus.DRAFT;
        return a;
    }

    /**
     * Sets the audience scope and channels in one step (replacing any prior values).
     *
     * @param areaIds      geo area public ids (may be empty for a role/category-only audience).
     * @param categoryId   optional category public id, or {@code null}.
     * @param audienceRole optional role-name narrowing, or {@code null}.
     * @param channels     the delivery channels (must be non-empty by the time it publishes).
     */
    public void targetAudience(Set<UUID> areaIds, UUID categoryId, String audienceRole,
                               Set<Channel> channels) {
        this.audienceAreaIds = new LinkedHashSet<>(areaIds);
        this.categoryId = categoryId;
        this.audienceRole = audienceRole;
        this.channels = new LinkedHashSet<>(channels);
    }

    /**
     * Sets the publish/expiry window.
     *
     * @param publishAt when to go live (UTC), or {@code null} for immediately.
     * @param expireAt  when to stop showing (UTC), or {@code null} for never.
     */
    public void schedule(Instant publishAt, Instant expireAt) {
        this.publishAt = publishAt;
        this.expireAt = expireAt;
    }

    /** Replaces the attachment refs (object-store keys). */
    public void setAttachmentRefs(Set<String> refs) {
        this.attachmentRefs = new LinkedHashSet<>(refs);
    }

    /** Flags the announcement for moderation hold (new/untrusted author) — blocks publish (US-4.1). */
    public void hold() {
        this.moderationHeld = true;
    }

    /** Clears the moderation hold (a moderator approved it). Does not itself publish. */
    public void clearHold() {
        this.moderationHeld = false;
    }

    /**
     * Transitions toward going live, honouring the moderation gate and the schedule.
     *
     * <p>WHY this method owns the transition (not the service writing {@code status} directly): the
     * legal transitions are a domain invariant (PRD §12). A held announcement may never reach
     * {@code PUBLISHED}; one with a future {@code publishAt} becomes {@code SCHEDULED} (the scheduler
     * promotes it later); otherwise it becomes {@code PUBLISHED} now.</p>
     *
     * @param now the current instant (injected via the clock port for testability).
     * @throws IllegalStateException if the announcement is still held for moderation.
     */
    public void publish(Instant now) {
        if (moderationHeld) {
            throw new IllegalStateException("Announcement is held for moderation; cannot publish");
        }
        if (publishAt != null && publishAt.isAfter(now)) {
            this.status = AnnouncementStatus.SCHEDULED;
        } else {
            this.status = AnnouncementStatus.PUBLISHED;
        }
    }

    /** Marks the announcement expired (scheduler, once {@code expireAt} has passed). */
    public void expire() {
        this.status = AnnouncementStatus.EXPIRED;
    }

    /** @return the authoring profile's public id. */
    public UUID getAuthorProfileId() {
        return authorProfileId;
    }

    /** @return the headline. */
    public String getTitle() {
        return title;
    }

    /** @return the Swahili body. */
    public String getBodySw() {
        return bodySw;
    }

    /** @return the English body, or {@code null}. */
    public String getBodyEn() {
        return bodyEn;
    }

    /** @return the tagged category public id, or {@code null}. */
    public UUID getCategoryId() {
        return categoryId;
    }

    /** @return the audience role-name narrowing, or {@code null}. */
    public String getAudienceRole() {
        return audienceRole;
    }

    /** @return the lifecycle status. */
    public AnnouncementStatus getStatus() {
        return status;
    }

    /** @return whether this announcement is held for moderation. */
    public boolean isModerationHeld() {
        return moderationHeld;
    }

    /** @return when to go/went live (UTC), or {@code null}. */
    public Instant getPublishAt() {
        return publishAt;
    }

    /** @return when to stop showing (UTC), or {@code null}. */
    public Instant getExpireAt() {
        return expireAt;
    }

    /** @return the targeted geo area public ids (defensive copy). */
    public Set<UUID> getAudienceAreaIds() {
        return new LinkedHashSet<>(audienceAreaIds);
    }

    /** @return the delivery channels (defensive copy). */
    public Set<Channel> getChannels() {
        return new LinkedHashSet<>(channels);
    }

    /** @return the attachment object-store keys (defensive copy). */
    public Set<String> getAttachmentRefs() {
        return new LinkedHashSet<>(attachmentRefs);
    }
}
