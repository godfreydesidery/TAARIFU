package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.communications.api.event.AnnouncementPublished;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Publishes geo-targeted announcements, holding new/untrusted authors for moderation (PRD §12, M4, US-4.1).
 *
 * <p>Responsibility: the use-case orchestration for composing and publishing an announcement. It owns the
 * transaction boundary, validates the channel/audience input into domain types, applies the
 * <b>moderation-hold</b> gate for untrusted authors (US-4.1, UC-G03), drives the {@code Announcement}
 * domain transition, and — on a successful publish — raises the {@link AnnouncementPublished} event that
 * triggers asynchronous feed fan-out + notification dispatch (PRD §13/§16).</p>
 *
 * <p><b>Moderation hold</b> (US-4.1): an announcement by a <i>trusted</i> author (official/admin/an
 * established author) publishes immediately; a <i>new/untrusted</i> author's announcement is held — it is
 * created in {@code DRAFT} with {@code moderationHeld=true} and cannot reach {@code PUBLISHED} until a
 * moderator clears the hold (the moderation review lives in the {@code moderation} module). The trust
 * decision is computed by the caller from the authenticated principal's roles/tier and passed in, so this
 * service stays free of identity internals (ARCHITECTURE §3.2). TODO(wiring): subscribe to the moderation
 * approval event to auto-publish a cleared hold.</p>
 *
 * <p>WHY the event is appended to the transactional <b>outbox</b> (ADR-0014), not raised in-process:
 * {@link OutboxWriter#append} runs with {@code Propagation.MANDATORY} inside <i>this</i> service's
 * {@code @Transactional}, so the {@code Announcement} row and the {@link AnnouncementPublished} outbox row
 * commit (or roll back) <b>atomically</b> — a crash between "published" and "fanned out" can never drop the
 * notification fan-out (PRD §15 DI3), which an {@code ApplicationEventPublisher}/{@code AFTER_COMMIT}
 * listener could. The author's publish call still returns fast: the {@code OutboxRelay} dispatches the
 * fan-out asynchronously, off the request thread, and at-least-once with idempotent handlers
 * ({@code AnnouncementPublishedHandler}).</p>
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final OutboxWriter outboxWriter;
    private final ClockPort clock;

    /**
     * @param announcementRepository announcement persistence.
     * @param outboxWriter           transactional-outbox writer; appends the {@link AnnouncementPublished}
     *                               event in this service's transaction so it commits atomically with the
     *                               publish (ADR-0014 §2) and is relayed to the fan-out handler.
     * @param clock                  injectable "now" for scheduling/transition (testability).
     */
    public AnnouncementService(AnnouncementRepository announcementRepository,
                               OutboxWriter outboxWriter,
                               ClockPort clock) {
        this.announcementRepository = announcementRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /**
     * Composes and (if the author is trusted) publishes an announcement.
     *
     * @param authorProfileId the authoring profile's public id (the authenticated caller).
     * @param trustedAuthor   whether the author is trusted (official/admin/established) — if {@code false}
     *                        the announcement is held for moderation before it can publish (US-4.1).
     * @param title           headline.
     * @param bodySw          Swahili body (required).
     * @param bodyEn          English body, or {@code null}.
     * @param areaIds         targeted geo area public ids (may be empty if role/category-targeted).
     * @param categoryId      tagged category public id, or {@code null}.
     * @param audienceRole    role-name narrowing, or {@code null}.
     * @param channelNames    channel enum names (validated here).
     * @param attachmentRefs  attachment object-store keys (may be empty/null).
     * @param publishAt       when to go live (UTC), or {@code null} for immediately.
     * @param expireAt        when to stop showing (UTC), or {@code null} for never.
     * @return the persisted announcement (PUBLISHED/SCHEDULED if trusted, DRAFT+held otherwise).
     * @throws ApiException {@link ErrorCode#VALIDATION_FAILED} if no channel resolves or the window is
     *                      invalid (expire ≤ publish).
     */
    @Transactional
    public Announcement publish(UUID authorProfileId, boolean trustedAuthor,
                                String title, String bodySw, String bodyEn,
                                Set<UUID> areaIds, UUID categoryId, String audienceRole,
                                Set<String> channelNames, Set<String> attachmentRefs,
                                Instant publishAt, Instant expireAt) {
        Set<Channel> channels = parseChannels(channelNames);
        validateWindow(publishAt, expireAt);

        Announcement a = Announcement.draft(authorProfileId, title, bodySw, bodyEn);
        a.targetAudience(
                areaIds == null ? Set.of() : areaIds,
                categoryId,
                audienceRole,
                channels);
        a.schedule(publishAt, expireAt);
        if (attachmentRefs != null && !attachmentRefs.isEmpty()) {
            a.setAttachmentRefs(attachmentRefs);
        }

        if (!trustedAuthor) {
            // New/untrusted author → hold for moderation; stays DRAFT, not published (US-4.1, UC-G03).
            a.hold();
            return announcementRepository.save(a);
        }

        a.publish(clock.now());
        Announcement saved = announcementRepository.save(a);
        emitIfPublished(saved);
        return saved;
    }

    /**
     * Lists an author's own announcements (any status), paged — the author management view.
     *
     * @param authorProfileId the authoring profile's public id.
     * @param pageable        paging/sorting.
     * @return a page of the author's announcements.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Announcement> listMine(
            UUID authorProfileId, org.springframework.data.domain.Pageable pageable) {
        return announcementRepository.findByAuthorProfileId(authorProfileId, pageable);
    }

    /**
     * Fetches a single announcement for the <b>public, citizen-readable detail view</b> — the feed-detail
     * screen's full-body read (the mobile agent's gap: only the lean feed item + author-scoped
     * {@code /mine} existed). Returns the announcement <b>only if it is live and citizen-visible</b>;
     * anything else is reported as <i>not found</i> so a draft, scheduled, expired, moderation-held, or
     * soft-deleted announcement is never leaked to the public.
     *
     * <p><b>Visibility rule</b> (PRD §22.6 "Public civic graph … announcements", AC-T0 guest read, SR-3
     * "active announcements"): an announcement is publicly readable iff it is {@code PUBLISHED}, has reached
     * its {@code publishAt} (or has none), and has not passed its {@code expireAt} (or has none) — the exact
     * predicate the personalised-feed query enforces ({@link AnnouncementRepository#findFeed}). Keeping the
     * same predicate in one place means the detail view can never reveal something the feed would hide.</p>
     *
     * <p>WHY a {@code 404} (not {@code 403}) for a hidden/expired/held announcement: existence itself is
     * privileged here. A {@code 403} would confirm the id refers to a real-but-unpublished announcement
     * (an enumeration/leak vector, PRD §18); {@code NOT_FOUND} reveals nothing about drafts in flight.
     * Soft-deleted rows are already excluded by the entity's {@code @SQLRestriction}, so they surface as
     * a clean miss too.</p>
     *
     * <p>WHY no authorization is enforced here (the controller uses {@code permitAll()}): published
     * announcements are public reference data — a guest may read them (PRD §22.6, AC-T0). The audience
     * scope ({@code audienceAreaIds}/role/category) governs <b>feed targeting and notification fan-out</b>,
     * not who may read a published announcement by its id; it is therefore intentionally <i>not</i> a read
     * gate (a citizen linked to an announcement may open it regardless of their own area).</p>
     *
     * @param publicId the announcement's public id.
     * @return the live, citizen-visible announcement.
     * @throws ResourceNotFoundException if no announcement with that id exists, or it is not currently
     *                                   {@code PUBLISHED} and within its publish/expiry window (drafts,
     *                                   scheduled, expired, moderation-held, and soft-deleted all 404).
     */
    @Transactional(readOnly = true)
    public Announcement getPublicDetail(UUID publicId) {
        Announcement a = announcementRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "communications.announcement.notFound", publicId));
        if (!isCitizenVisible(a, clock.now())) {
            // Hidden (draft/scheduled/expired/held/not-yet-live) → indistinguishable from a non-existent id.
            throw new ResourceNotFoundException("communications.announcement.notFound", publicId);
        }
        return a;
    }

    /**
     * The single citizen-visibility predicate for a published announcement, mirroring the feed query's
     * window (PRD §22.6, UC-G04): {@code PUBLISHED} and within {@code [publishAt, expireAt)}.
     *
     * @param a   the announcement.
     * @param now the current instant (clock port — testable).
     * @return {@code true} iff the announcement is live and citizen-readable right now.
     */
    private boolean isCitizenVisible(Announcement a, Instant now) {
        return a.getStatus() == AnnouncementStatus.PUBLISHED
                && (a.getPublishAt() == null || !a.getPublishAt().isAfter(now))
                && (a.getExpireAt() == null || a.getExpireAt().isAfter(now));
    }

    /**
     * Clears a moderation hold and publishes (the moderator-approval path). Idempotent: a non-held or
     * already-published announcement is returned unchanged.
     *
     * <p>TODO(wiring): this is invoked today only by tests / a future moderation-decision consumer; the
     * moderator authorisation is enforced at that call site, not here.</p>
     *
     * @param announcementPublicId the held announcement's public id.
     * @return the now-published announcement.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if no such announcement.
     */
    @Transactional
    public Announcement approveAndPublish(UUID announcementPublicId) {
        Announcement a = announcementRepository.findByPublicId(announcementPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        a.clearHold();
        a.publish(clock.now());
        Announcement saved = announcementRepository.save(a);
        emitIfPublished(saved);
        return saved;
    }

    /**
     * Appends the {@link AnnouncementPublished} event to the transactional outbox — only when the
     * announcement actually went live now — so the fan-out intent commits atomically with the publish
     * (ADR-0014 §2/§5a). Called inside this service's {@code @Transactional}; {@link OutboxWriter#append}
     * (Propagation.MANDATORY) joins that transaction, guaranteeing the event row and the announcement row
     * share one commit. The payload carries ids/codes/enums only — never the title/body (PRD §18); the
     * fan-out handler re-reads any detail it needs by id (ADR-0013).
     *
     * @param a the just-saved announcement; the event is appended only if it is {@code PUBLISHED}.
     */
    private void emitIfPublished(Announcement a) {
        if (a.getStatus() == AnnouncementStatus.PUBLISHED) {
            AnnouncementPublished payload = new AnnouncementPublished(
                    a.getPublicId(),
                    a.getAuthorProfileId(),
                    a.getAudienceAreaIds(),
                    a.getCategoryId(),
                    a.getAudienceRole(),
                    a.getChannels().stream().map(Channel::name)
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    a.getPublishAt() == null ? clock.now() : a.getPublishAt());
            outboxWriter.append(EventEnvelope.of(
                    AnnouncementPublished.EVENT_TYPE,
                    AnnouncementPublished.AGGREGATE_TYPE,
                    a.getPublicId(),
                    payload,
                    payload.publishedAt()));
        }
    }

    /** Parses + validates channel names; an unknown/empty set is a localised validation failure. */
    private Set<Channel> parseChannels(Set<String> channelNames) {
        if (channelNames == null || channelNames.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        Set<Channel> channels = new LinkedHashSet<>();
        for (String name : channelNames) {
            try {
                channels.add(Channel.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED);
            }
        }
        return channels;
    }

    /** Rejects an inverted schedule window (expire ≤ publish) with a localised validation failure. */
    private void validateWindow(Instant publishAt, Instant expireAt) {
        if (publishAt != null && expireAt != null && !expireAt.isAfter(publishAt)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
