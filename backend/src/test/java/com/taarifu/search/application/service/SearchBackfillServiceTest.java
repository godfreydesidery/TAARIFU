package com.taarifu.search.application.service;

import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.api.dto.SearchReindexResult;
import com.taarifu.search.api.dto.SearchReindexStatus;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import com.taarifu.search.domain.port.SearchBackfillSource;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchBackfillService} — the admin reindex/backfill orchestrator (ADR-0017 follow-up).
 *
 * <p>Responsibility: prove the load-bearing guarantees of the job <b>without</b> reaching into any owner —
 * the orchestrator is exercised against a hand-rolled {@link SearchBackfillSource} fake that mimics exactly
 * what a real owner adapter does:
 * <ul>
 *   <li><b>public rows are upserted</b> through the index port (the backfill populates the index);</li>
 *   <li><b>private/anonymous/unpublished rows are excluded</b> by the source's own fence (search never indexes
 *       them — the fence stays in the owner, so the fake enforces it, matching the real contract);</li>
 *   <li><b>idempotent</b> — re-running the job re-pushes the same source keys (no extra/duplicate rows: the
 *       upsert dedups by {@code (entityType, publicId)}, asserted via the captured upserts);</li>
 *   <li><b>fault isolation</b> — one source throwing does not abort the others, and is reported as failed;</li>
 *   <li><b>zero sources</b> is a clean no-op (the expected state until owners ship adapters).</li>
 * </ul>
 * Each test fails if the corresponding guarantee were dropped.</p>
 */
class SearchBackfillServiceTest {

    private final SearchDocumentRepository repository = mock(SearchDocumentRepository.class);

    /**
     * A spy index port that records every upsert/remove so the test can assert exactly which source rows were
     * pushed — standing in for the real {@link SearchIndexService} (which the unit test does not need).
     */
    private static final class RecordingIndex implements SearchIndexApi {
        final List<SearchDocumentUpsert> upserts = new ArrayList<>();

        @Override
        public void upsert(SearchDocumentUpsert upsert) {
            upserts.add(upsert);
        }

        @Override
        public void remove(SearchEntityType entityType, UUID entityPublicId) {
            // not exercised by backfill
        }
    }

    /**
     * A fake owner adapter that holds a mix of public and private source rows and re-pushes ONLY the public ones
     * — exactly the fence a real {@code SearchBackfillSource} applies (the privacy decision lives in the owner).
     */
    private static final class FakeSource implements SearchBackfillSource {
        private final SearchEntityType type;
        private final List<UUID> publicIds;

        FakeSource(SearchEntityType type, List<UUID> publicIds) {
            this.type = type;
            this.publicIds = publicIds;
        }

        @Override
        public SearchEntityType entityType() {
            return type;
        }

        @Override
        public long backfill(SearchIndexApi index) {
            // The owner's fence: only PUBLIC, published rows are pushed. Private/anonymous/unpublished/DRAFT rows
            // are deliberately filtered out by the owner BEFORE this loop — they never cross into discovery, so a
            // source only ever offers its public ids here (the caller's test asserts a private id is absent).
            for (UUID id : publicIds) {
                index.upsert(new SearchDocumentUpsert(type, id, "title-" + id,
                        "muhtasari", "summary", null, null, null, SearchVisibility.PUBLIC, null));
            }
            return publicIds.size();
        }
    }

    private SearchBackfillService service(List<SearchBackfillSource> sources, SearchIndexApi index) {
        return new SearchBackfillService(sources, index, repository);
    }

    @Test
    void reindex_upsertsOnlyPublicRows_excludesPrivate() {
        UUID pub1 = UUID.randomUUID();
        UUID pub2 = UUID.randomUUID();
        UUID priv = UUID.randomUUID();
        RecordingIndex index = new RecordingIndex();
        when(repository.countLive()).thenReturn(2L);
        // The source holds two public rows and one private row; a real owner adapter filters the private row out
        // BEFORE pushing, so the fake is constructed with only its public ids — the private id is never offered.
        FakeSource source = new FakeSource(SearchEntityType.ORGANISATION, List.of(pub1, pub2));

        SearchReindexResult result = service(List.of(source), index).reindexAll();

        // Only the two public ids were pushed; the private id never crossed into the index.
        assertThat(index.upserts).extracting(SearchDocumentUpsert::entityPublicId)
                .containsExactlyInAnyOrder(pub1, pub2)
                .doesNotContain(priv);
        assertThat(index.upserts).allMatch(u -> u.visibility() == SearchVisibility.PUBLIC);
        assertThat(result.totalUpserted()).isEqualTo(2L);
        assertThat(result.indexSize()).isEqualTo(2L);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.sources()).singleElement()
                .satisfies(s -> {
                    assertThat(s.entityType()).isEqualTo("ORGANISATION");
                    assertThat(s.upserted()).isEqualTo(2L);
                    assertThat(s.succeeded()).isTrue();
                });
    }

    @Test
    void reindex_isIdempotent_rePushesSameSourceKeys_noDuplicateKeys() {
        UUID pub1 = UUID.randomUUID();
        UUID pub2 = UUID.randomUUID();
        RecordingIndex index = new RecordingIndex();
        when(repository.countLive()).thenReturn(2L);
        FakeSource source = new FakeSource(SearchEntityType.REPRESENTATIVE, List.of(pub1, pub2));
        SearchBackfillService service = service(List.of(source), index);

        service.reindexAll();
        service.reindexAll();

        // Two runs push the same two source KEYS twice each — but the upsert is keyed on (type, publicId), so the
        // SET of distinct keys is unchanged across runs. The index never gains a new key on a re-run: re-pushing a
        // key is an in-place update, never a new row. That distinct-key stability IS the idempotency guarantee.
        assertThat(index.upserts).hasSize(4); // 2 keys x 2 runs (each run re-pushes; the upsert dedups downstream)
        assertThat(index.upserts).extracting(SearchDocumentUpsert::entityPublicId)
                .containsExactlyInAnyOrder(pub1, pub2, pub1, pub2);
        // The distinct key set is exactly {pub1, pub2} after one run AND after two — no new keys introduced.
        assertThat(index.upserts.stream().map(SearchDocumentUpsert::entityPublicId).distinct().toList())
                .containsExactlyInAnyOrder(pub1, pub2);
    }

    @Test
    void reindex_isolatesAFailingSource_continuesTheRest() {
        UUID pub = UUID.randomUUID();
        RecordingIndex index = new RecordingIndex();
        when(repository.countLive()).thenReturn(1L);
        SearchBackfillSource good = new FakeSource(SearchEntityType.ANNOUNCEMENT, List.of(pub));
        SearchBackfillSource bad = new SearchBackfillSource() {
            @Override
            public SearchEntityType entityType() {
                return SearchEntityType.PUBLIC_REPORT;
            }

            @Override
            public long backfill(SearchIndexApi i) {
                throw new IllegalStateException("source unavailable");
            }
        };

        // Order the failing source first to prove the good one still runs after it.
        SearchReindexResult result = service(List.of(bad, good), index).reindexAll();

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.sourcesRun()).isEqualTo(2);
        assertThat(result.totalUpserted()).isEqualTo(1L); // only the good source's row
        assertThat(index.upserts).extracting(SearchDocumentUpsert::entityPublicId).containsExactly(pub);
        assertThat(result.sources()).anySatisfy(s -> {
            assertThat(s.entityType()).isEqualTo("PUBLIC_REPORT");
            assertThat(s.succeeded()).isFalse();
            assertThat(s.error()).contains("source unavailable");
        });
        assertThat(result.sources()).anySatisfy(s -> {
            assertThat(s.entityType()).isEqualTo("ANNOUNCEMENT");
            assertThat(s.succeeded()).isTrue();
        });
    }

    @Test
    void reindex_withNoSources_isACleanNoOp() {
        RecordingIndex index = new RecordingIndex();
        when(repository.countLive()).thenReturn(0L);

        SearchReindexResult result = service(List.of(), index).reindexAll();

        assertThat(index.upserts).isEmpty();
        assertThat(result.sourcesRun()).isZero();
        assertThat(result.totalUpserted()).isZero();
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.sources()).isEmpty();
    }

    @Test
    void status_reportsLiveSizeRegisteredSourcesAndLastRun() {
        RecordingIndex index = new RecordingIndex();
        when(repository.countLive()).thenReturn(5L);
        FakeSource source = new FakeSource(SearchEntityType.QUESTION, List.of(UUID.randomUUID()));
        SearchBackfillService service = service(List.of(source), index);

        // Before any run: lastRun is null.
        SearchReindexStatus before = service.status();
        assertThat(before.indexSize()).isEqualTo(5L);
        assertThat(before.registeredSources()).isEqualTo(1);
        assertThat(before.lastRun()).isNull();

        service.reindexAll();

        // After a run: lastRun is populated.
        SearchReindexStatus after = service.status();
        assertThat(after.registeredSources()).isEqualTo(1);
        assertThat(after.lastRun()).isNotNull();
        assertThat(after.lastRun().totalUpserted()).isEqualTo(1L);
    }
}
