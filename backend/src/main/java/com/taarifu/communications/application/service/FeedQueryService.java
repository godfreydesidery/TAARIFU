package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.communications.api.dto.FeedItemDto;
import com.taarifu.communications.application.mapper.CommunicationsMapper;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.SubscriptionTargetType;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.domain.repository.SubscriptionRepository;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Builds a citizen's personalised feed from their follows + area-matched announcements
 * (PRD §12 UC-G04, §22.6, M4).
 *
 * <p>Responsibility: a read-only application service that assembles the feed for one caller. It reads the
 * caller's followed <b>area</b> and <b>category</b> target ids from their {@code Subscription} set, then
 * returns the live, non-expired announcements whose audience matches — newest first — as lean
 * {@link FeedItemDto}s in the caller's locale (PRD §15 data budget, ADR-0010). It owns the transaction
 * boundary (read-only) and returns DTOs, never entities.</p>
 *
 * <p>WHY follows are read here and passed into the announcement query (rather than a single mega-join
 * across modules): subscriptions are this module's own table, so the join is in-module; the announcement
 * audience is matched against the resolved follow-id sets. This keeps the feed query indexed and bounded
 * and avoids any cross-module table reach (ARCHITECTURE §3.2).</p>
 *
 * <p>WHY representative-follows are not yet a feed dimension: an announcement is currently matched by area
 * and category (its stored audience), not by author-followed-as-representative. Author→representative
 * resolution is owned by {@code institutions}; that dimension is added when that module is wired
 * (TODO(wiring): include REPRESENTATIVE follows by resolving the author's representative id).</p>
 */
@Service
@Transactional(readOnly = true)
public class FeedQueryService {

    private final SubscriptionRepository subscriptionRepository;
    private final AnnouncementRepository announcementRepository;
    private final CommunicationsMapper mapper;
    private final ClockPort clock;

    /**
     * @param subscriptionRepository follow-edge persistence (this module's own table).
     * @param announcementRepository announcement persistence (the feed query).
     * @param mapper                 entity→DTO mapper (localised snippet).
     * @param clock                  injectable "now" for the publish/expiry window.
     */
    public FeedQueryService(SubscriptionRepository subscriptionRepository,
                            AnnouncementRepository announcementRepository,
                            CommunicationsMapper mapper,
                            ClockPort clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.announcementRepository = announcementRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Returns the caller's personalised feed page.
     *
     * @param callerProfileId the authenticated caller's profile public id.
     * @param pageable        bounded paging/sorting from {@code PageRequestFactory}.
     * @return a page of {@link FeedItemDto}, newest announcements first.
     */
    public Page<FeedItemDto> getFeed(UUID callerProfileId, Pageable pageable) {
        Set<UUID> followedAreas =
                subscriptionRepository.findTargetIds(callerProfileId, SubscriptionTargetType.AREA);
        Set<UUID> followedCategories =
                subscriptionRepository.findTargetIds(callerProfileId, SubscriptionTargetType.CATEGORY);

        // A citizen following nothing still gets an (empty) feed page rather than an error — the
        // empty-platform/cold-start reality (PRD §28 R9). The query's IN-clauses are guarded against
        // empty sets so they never match-all by accident (deny-by-default): substitute a sentinel.
        Set<UUID> areaIds = followedAreas.isEmpty() ? Set.of(NO_MATCH) : followedAreas;
        Set<UUID> categoryIds = followedCategories.isEmpty() ? Set.of(NO_MATCH) : followedCategories;

        Page<Announcement> page =
                announcementRepository.findFeed(areaIds, categoryIds, clock.now(), pageable);
        var locale = LocaleContextHolder.getLocale();
        return page.map(a -> mapper.toFeedItemDto(a, locale));
    }

    /**
     * A sentinel UUID that matches no real area/category — used to keep an {@code IN (:ids)} clause from
     * being empty (which some JPA providers reject) without ever matching a real row (deny-by-default).
     */
    private static final UUID NO_MATCH = new UUID(0L, 0L);
}
