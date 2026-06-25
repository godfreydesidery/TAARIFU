package com.taarifu.communications;

import com.taarifu.common.outbox.OutboxWriter;
import com.taarifu.communications.application.service.AnnouncementService;
import com.taarifu.communications.domain.model.Announcement;
import com.taarifu.communications.domain.model.enums.AnnouncementStatus;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.repository.AnnouncementRepository;
import com.taarifu.communications.infrastructure.adapter.AnnouncementSearchBackfillSource;
import com.taarifu.reporting.api.IssueCategoryQueryApi;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnnouncementSearchBackfillSource} — communications' adapter of the search backfill port
 * (ADR-0017 follow-up; ADR-0013 §7 cross-module {@code domain.port} injection).
 *
 * <p>Responsibility: pin the discovery-backfill invariants a reviewer must never see silently regress — a
 * {@code PUBLISHED} announcement is re-pushed into the index with the public-safe projection (title + localised
 * snippet + area/category facets, {@code PUBLIC} visibility, opaque ids only — never the full body, the
 * {@code moderationHeld} flag, attachments, or the schedule); the source scan is batched across pages and walks
 * every page to exhaustion; an empty source returns 0 without throwing; and the upsert count reflects only the
 * rows actually indexed.</p>
 *
 * <p><b>WHY a REAL {@link AnnouncementService} (not a mock):</b> the whole point of the adapter is that the
 * backfill reuses the live producer's fence + projection so they cannot drift (ADR-0017 §1, DRY). Mocking
 * {@code reindexForDiscovery} would test a stub, not the fence. So this test wires a genuine
 * {@link AnnouncementService} (with a mocked {@link SearchIndexApi}/{@link AnnouncementRepository} and the other
 * collaborators the backfill path never touches) and asserts on the <b>real</b> projection that flows out —
 * exactly what production indexes. Mockito only — no database.</p>
 */
class AnnouncementSearchBackfillSourceTest {

    private AnnouncementRepository announcementRepository;
    private SearchIndexApi searchIndex;
    private AnnouncementService announcementService;
    private AnnouncementSearchBackfillSource backfillSource;

    private final UUID author = UUID.randomUUID();
    private final UUID area = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        announcementRepository = mock(AnnouncementRepository.class);
        searchIndex = mock(SearchIndexApi.class);
        // A REAL AnnouncementService so the genuine reindexForDiscovery fence + projection is exercised; only its
        // collaborators are stubbed. The backfill never calls publish/approve/expire, so the outbox, clock, and
        // category-validation ports are unused on this path.
        announcementService = new AnnouncementService(
                announcementRepository, mock(OutboxWriter.class), new FixedClock(Instant.parse("2026-06-25T00:00:00Z")),
                searchIndex, mock(IssueCategoryQueryApi.class));
        backfillSource = new AnnouncementSearchBackfillSource(announcementRepository, announcementService);
    }

    @Test
    void entityType_isAnnouncement() {
        // The orchestrator groups results by this; one adapter owns exactly one type (ADR-0017).
        assertThat(backfillSource.entityType()).isEqualTo(SearchEntityType.ANNOUNCEMENT);
    }

    @Test
    void backfill_upsertsPublishedAnnouncements_withPublicSafeProjection_andCountsThem() {
        // A PUBLISHED announcement is re-pushed into discovery with the SAME projection the live path builds:
        // title + localised SW/EN snippet + area/category facets + PUBLIC visibility, public-display + opaque ids.
        Announcement a = published("Maji Tangazo", "Maji yatakatika kesho.", "Water will be cut tomorrow.");
        Announcement b = published("Barabara", "Barabara itafungwa.", null);
        whenPagePublished(List.of(a, b));

        long upserted = backfillSource.backfill(searchIndex);

        assertThat(upserted).isEqualTo(2L);
        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(searchIndex, times(2)).upsert(captor.capture());
        verify(searchIndex, never()).remove(any(), any());
        // The projection is the live producer's: public-safe, opaque ids only, no PII.
        SearchDocumentUpsert first = captor.getAllValues().get(0);
        assertThat(first.entityType()).isEqualTo(SearchEntityType.ANNOUNCEMENT);
        assertThat(first.entityPublicId()).isEqualTo(a.getPublicId());
        assertThat(first.title()).isEqualTo("Maji Tangazo");
        assertThat(first.snippetSw()).isEqualTo("Maji yatakatika kesho.");
        assertThat(first.snippetEn()).isEqualTo("Water will be cut tomorrow.");
        assertThat(first.areaId()).isEqualTo(area);
        assertThat(first.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        // authoredByAccountId is the author (visibility-maintenance only); never the full body or a PII field.
        assertThat(first.authoredByAccountId()).isEqualTo(author);
    }

    @Test
    void backfill_isBatched_walksEveryPage() {
        // The scan is paged (BATCH_SIZE) and must walk EVERY page to exhaustion — a backfill that stops after
        // page one would silently miss most of the corpus. Five rows paged 2 + 2 + 1.
        List<Announcement> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(published("T" + i, "Mwili " + i, null));
        }
        when(announcementRepository.findByStatusOrderById(eq(AnnouncementStatus.PUBLISHED), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    return slice(all, p.getPageNumber(), 2);
                });

        long upserted = backfillSource.backfill(searchIndex);

        assertThat(upserted).isEqualTo(5L);
        verify(searchIndex, times(5)).upsert(any());
        // Three pages requested (0,1,2): the walk did not stop early and did not loop past the end.
        verify(announcementRepository, times(3))
                .findByStatusOrderById(eq(AnnouncementStatus.PUBLISHED), any(Pageable.class));
    }

    @Test
    void backfill_emptySource_returnsZero_neverThrows() {
        // The contract: an empty source returns 0, never throws (the orchestrator must be able to drive a module
        // with no rows). No index call of any kind.
        whenPagePublished(List.of());

        long upserted = backfillSource.backfill(searchIndex);

        assertThat(upserted).isZero();
        verify(searchIndex, never()).upsert(any());
        verify(searchIndex, never()).remove(any(), any());
    }

    /** Stubs a single full page of the given PUBLISHED announcements (no further pages). */
    private void whenPagePublished(List<Announcement> announcements) {
        when(announcementRepository.findByStatusOrderById(eq(AnnouncementStatus.PUBLISHED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(announcements, PageRequest.of(0, 200), announcements.size()));
    }

    /** Builds a PUBLISHED announcement targeting {@code area} over FEED, with the given title/bodies. */
    private Announcement published(String title, String bodySw, String bodyEn) {
        Announcement a = Announcement.draft(author, title, bodySw, bodyEn);
        a.targetAudience(Set.of(area), null, null, Set.of(Channel.FEED));
        a.publish(Instant.parse("2026-06-25T00:00:00Z"));
        assertThat(a.getStatus()).isEqualTo(AnnouncementStatus.PUBLISHED);
        return a;
    }

    /** Builds the page at {@code pageNumber} of {@code size} over {@code all} (for the batching walk test). */
    private static Page<Announcement> slice(List<Announcement> all, int pageNumber, int size) {
        int from = Math.min(pageNumber * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageImpl<>(all.subList(from, to), PageRequest.of(pageNumber, size), all.size());
    }
}
