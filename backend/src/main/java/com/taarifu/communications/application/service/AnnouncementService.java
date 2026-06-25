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
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
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
 *
 * <p><b>Discovery indexing (ADR-0017 §1):</b> on the went-live funnel ({@link #emitIfPublished}, reached by
 * both {@link #publish} and {@link #approveAndPublish}) this service also <b>pushes a public projection</b> of
 * the announcement to the search index via the {@link SearchIndexApi} published port (owner→search, an
 * {@code api → api} call — ADR-0013 §1). It indexes a {@code PUBLISHED} announcement and <b>removes</b> any
 * other state (held/scheduled/expired) so a draft, a moderation-held author's post, or an unpublished item is
 * never discoverable. Only <b>public-safe</b> fields are indexed — the public title + a localised body snippet
 * + the area facet — <b>never</b> the draft, the {@code moderationHeld} flag, an internal note, the full body,
 * or any PII (PRD §18, ADR-0017 §1). Indexing rides this service's transaction (a synchronous in-process call),
 * so the projection and the announcement row commit together; a search-index failure would roll the publish
 * back, which is acceptable here because the index call is a fast local DB upsert, not a remote hop.</p>
 */
@Service
public class AnnouncementService {

    /**
     * Max characters of body carried in the public discovery snippet — a lean, public preview only (PRD §15
     * data budget; well under ADR-0017's {@code snippet_*} 1024-char column). The snippet is a truncated prefix
     * of the public body; the full body is re-read from this module by id when a result is tapped (the index
     * never returns the full aggregate — ADR-0013).
     */
    private static final int DISCOVERY_SNIPPET_LENGTH = 280;

    private final AnnouncementRepository announcementRepository;
    private final OutboxWriter outboxWriter;
    private final ClockPort clock;
    private final SearchIndexApi searchIndex;

    /**
     * @param announcementRepository announcement persistence.
     * @param outboxWriter           transactional-outbox writer; appends the {@link AnnouncementPublished}
     *                               event in this service's transaction so it commits atomically with the
     *                               publish (ADR-0014 §2) and is relayed to the fan-out handler.
     * @param clock                  injectable "now" for scheduling/transition (testability).
     * @param searchIndex            the search module's published inbound index port (ADR-0017 §1); this
     *                               service pushes a public, PII-free announcement projection on publish and
     *                               removes it on any non-published state. Injected as the {@code api}
     *                               interface, never search's implementation/internals (ADR-0013 §1).
     */
    public AnnouncementService(AnnouncementRepository announcementRepository,
                               OutboxWriter outboxWriter,
                               ClockPort clock,
                               SearchIndexApi searchIndex) {
        this.announcementRepository = announcementRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.searchIndex = searchIndex;
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
        indexForDiscovery(saved);
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
        indexForDiscovery(saved);
        return saved;
    }

    /**
     * Takes an announcement out of public circulation — the <b>expiry / unpublish path</b> — and, critically,
     * <b>removes its discovery-index projection</b> so an expired or unpublished announcement does not linger
     * as a stale public search row (the wave-3 gap: publish upserted, but nothing removed when an announcement
     * left the published state).
     *
     * <p>WHY this method exists: the {@code Announcement.expire()} domain transition existed and the
     * {@link AnnouncementRepository#findDueForTransition expiry-sweep query} existed, but no application path
     * drove the transition — so a published announcement that passed its {@code expireAt} stopped appearing in
     * the feed (the feed query filters on the window) yet remained a {@code PUBLISHED} row in
     * {@code search_document}, discoverable forever. This closes that leak. An expiry scheduler/operator
     * unpublish action calls this; it transitions the row to {@code EXPIRED} and routes through the same single
     * {@link #indexForDiscovery(Announcement)} fence, whose non-PUBLISHED branch <b>removes</b> the projection
     * (idempotent — removing an absent/already-removed row is a no-op, ADR-0017 §1).</p>
     *
     * <p>Idempotent: an already-{@code EXPIRED} announcement is returned unchanged but still has its (absent)
     * projection re-removed, so a redelivered sweep or a double unpublish never errors.</p>
     *
     * @param announcementPublicId the announcement to expire/unpublish.
     * @return the now-{@code EXPIRED} announcement.
     * @throws ApiException {@link ErrorCode#NOT_FOUND} if no such announcement.
     */
    @Transactional
    public Announcement expire(UUID announcementPublicId) {
        Announcement a = announcementRepository.findByPublicId(announcementPublicId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        a.expire();
        Announcement saved = announcementRepository.save(a);
        // No longer PUBLISHED → indexForDiscovery removes the projection so the expired/unpublished
        // announcement is positively pulled from public discovery (the no-leak rule, PRD §18, ADR-0017 §1).
        indexForDiscovery(saved);
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

    /**
     * Synchronises this announcement's <b>discovery-index</b> projection with its lifecycle state by pushing
     * to the search module's published {@link SearchIndexApi} port (ADR-0017 §1; an {@code api → api}
     * cross-module call — ADR-0013 §1). Called on the went-live funnel after the row is saved, inside this
     * service's {@code @Transactional}.
     *
     * <p><b>Index ⇔ public visibility (the no-leak rule, PRD §18, ADR-0017 §1/§4):</b></p>
     * <ul>
     *   <li>{@code PUBLISHED} → <b>upsert</b> a {@code PUBLIC} projection so the announcement is discoverable;</li>
     *   <li>any other state (DRAFT/held, SCHEDULED, EXPIRED) → <b>remove</b> the projection so a draft, a
     *       moderation-held author's post, a not-yet-live, or an expired announcement is never discoverable.</li>
     * </ul>
     * The {@code remove} branch also makes a re-saved announcement that has <i>left</i> the published state drop
     * out of discovery — so this single call keeps the index honest on a future unpublish/expire path too
     * (idempotent: removing an unindexed row is a no-op — ADR-0017 §1).
     *
     * <p><b>🔒 What is indexed — public-safe only:</b> the public {@link Announcement#getTitle() title}, a short
     * localised body snippet (SW always; EN only if an English body exists), and the <b>area facet</b> (the
     * first audience area, the Ward-or-coarser filter dimension — ADR-0017 §2) plus the category facet. The
     * <b>full body, the {@code moderationHeld} flag, attachment refs, the schedule, and any internal/draft
     * field are NEVER indexed</b>. {@code visibility} is always {@code PUBLIC} (we only upsert a published row);
     * a published announcement is public civic data (PRD §22.6, AC-T0), so there is no {@code STAFF}-tier
     * announcement to gate. {@code authoredByAccountId} carries the author profile id solely for the
     * suspended-author visibility-maintenance sweep (ADR-0017 §3); it is never returned to a reader.</p>
     *
     * @param a the just-saved announcement; its current {@link Announcement#getStatus() status} decides
     *          upsert-vs-remove.
     */
    private void indexForDiscovery(Announcement a) {
        if (a.getStatus() == AnnouncementStatus.PUBLISHED) {
            UUID areaFacet = a.getAudienceAreaIds().stream().findFirst().orElse(null);
            searchIndex.upsert(new SearchDocumentUpsert(
                    SearchEntityType.ANNOUNCEMENT,
                    a.getPublicId(),
                    a.getTitle(),
                    discoverySnippet(a.getBodySw()),
                    discoverySnippet(a.getBodyEn()),
                    null,
                    areaFacet,
                    a.getCategoryId(),
                    SearchVisibility.PUBLIC,
                    a.getAuthorProfileId()));
        } else {
            // Not publicly visible (draft/held/scheduled/expired) → must not be discoverable. Idempotent.
            searchIndex.remove(SearchEntityType.ANNOUNCEMENT, a.getPublicId());
        }
    }

    /**
     * Builds a lean, public body snippet for the discovery index — a truncated prefix of the given body, or
     * {@code null} if the body is absent/blank.
     *
     * <p>WHY truncate: the index carries only a short public preview (PRD §15 data budget; ADR-0017 keeps
     * snippets short); the client re-reads the full body from this module by id when a result is tapped. This
     * is the public body the citizen already sees in the feed — no draft, no internal field — so it is safe to
     * index (PRD §18).</p>
     *
     * @param body the public body text (SW or EN), or {@code null}.
     * @return a snippet of at most {@link #DISCOVERY_SNIPPET_LENGTH} characters, or {@code null}.
     */
    private String discoverySnippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return body.length() <= DISCOVERY_SNIPPET_LENGTH
                ? body
                : body.substring(0, DISCOVERY_SNIPPET_LENGTH);
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
