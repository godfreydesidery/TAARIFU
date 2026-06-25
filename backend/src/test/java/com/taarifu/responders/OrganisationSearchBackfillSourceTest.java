package com.taarifu.responders;

import com.taarifu.responders.application.mapper.OrganisationSearchProjection;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import com.taarifu.responders.domain.repository.OrganisationRepository;
import com.taarifu.responders.infrastructure.adapter.OrganisationSearchBackfillSource;
import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrganisationSearchBackfillSource} — the responders module's
 * {@link com.taarifu.search.domain.port.SearchBackfillSource} adapter for the
 * {@link SearchEntityType#ORGANISATION} source (ADR-0017 follow-up; ADR-0013 §7) — with Mockito only
 * (no Docker), so they run in every local build.
 *
 * <p>Responsibility: pin the load-bearing guarantees of the backfill source without a real index:
 * <ul>
 *   <li>it re-pushes <b>every</b> non-deleted organisation as an ORGANISATION projection through
 *       {@link SearchIndexApi#upsert} (the backfill populates the index);</li>
 *   <li>the visibility decision is the SAME fence as the live producer — PUBLIC for a publicly-listable
 *       (ACTIVE + verified) org, STAFF for a PENDING/unverified one — because both reuse
 *       {@link OrganisationSearchProjection} (the fence cannot drift);</li>
 *   <li>it walks ALL pages (not just the first), so a corpus larger than one page is fully re-pushed;</li>
 *   <li>it is idempotent — a second run re-pushes the same source keys (the {@code (type, publicId)}
 *       upsert dedups downstream);</li>
 *   <li>an empty corpus is a clean no-op returning 0.</li>
 * </ul>
 * Each test fails if the corresponding guarantee were dropped.</p>
 */
class OrganisationSearchBackfillSourceTest {

    private OrganisationRepository organisationRepository;
    private OrganisationSearchBackfillSource source;

    /** A spy index port recording every upsert so the test can assert exactly which rows were pushed. */
    private static final class RecordingIndex implements SearchIndexApi {
        final List<SearchDocumentUpsert> upserts = new ArrayList<>();

        @Override
        public void upsert(SearchDocumentUpsert upsert) {
            upserts.add(upsert);
        }

        @Override
        public void remove(SearchEntityType entityType, UUID entityPublicId) {
            // backfill never removes
        }
    }

    @BeforeEach
    void setUp() {
        organisationRepository = mock(OrganisationRepository.class);
        source = new OrganisationSearchBackfillSource(organisationRepository, new OrganisationSearchProjection());
    }

    @Test
    void entityType_isOrganisation() {
        assertThat(source.entityType()).isEqualTo(SearchEntityType.ORGANISATION);
    }

    @Test
    void backfill_pushesEveryOrg_withLiveProducerVisibilityFence() {
        // A publicly-listable org (ACTIVE + verified) → PUBLIC; a PENDING/unverified org → STAFF. The backfill
        // must re-push BOTH (the live path upserts on every org, setting STAFF for non-listable ones), so a
        // staff-visible unverified org is faithfully re-indexed — never dropped, never wrongly made PUBLIC.
        Organisation listable = Organisation.create("TANESCO", OrganisationType.PARASTATAL);
        listable.changeStatus(OrganisationStatus.ACTIVE);
        listable.setVerified(true);
        Organisation pending = Organisation.create("New Co", OrganisationType.PRIVATE_COMPANY);

        when(organisationRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(listable, pending), PageRequest.of(0, 200), 2));

        RecordingIndex index = new RecordingIndex();
        long count = source.backfill(index);

        assertThat(count).isEqualTo(2L);
        assertThat(index.upserts).hasSize(2);
        assertThat(index.upserts).allMatch(u -> u.entityType() == SearchEntityType.ORGANISATION);
        // The listable org is PUBLIC; the pending org is STAFF — the same fence as the live producer.
        assertThat(index.upserts).anySatisfy(u -> {
            assertThat(u.title()).isEqualTo("TANESCO");
            assertThat(u.keywords()).isEqualTo("PARASTATAL");
            assertThat(u.visibility()).isEqualTo(SearchVisibility.PUBLIC);
            assertThat(u.authoredByAccountId()).isNull(); // a directory entity has no author — no PII
        });
        assertThat(index.upserts).anySatisfy(u -> {
            assertThat(u.title()).isEqualTo("New Co");
            assertThat(u.visibility()).isEqualTo(SearchVisibility.STAFF);
        });
    }

    @Test
    void backfill_walksAllPages() {
        // Two pages of size 1 over a corpus of 2: the source MUST follow nextPageable() and push BOTH pages'
        // rows, not just the first. We use page size 1 (total 2 > size 1) so PageImpl.hasNext() is true after
        // the first page — the adapter must then request and process the second. Fails if it stops after page 0.
        Organisation a = Organisation.create("A", OrganisationType.PARASTATAL);
        Organisation b = Organisation.create("B", OrganisationType.PARASTATAL);
        Page<Organisation> firstPage = new PageImpl<>(List.of(a), PageRequest.of(0, 1), 2);   // hasNext() == true
        Page<Organisation> secondPage = new PageImpl<>(List.of(b), PageRequest.of(1, 1), 2);  // hasNext() == false

        when(organisationRepository.findAllBy(any(Pageable.class)))
                .thenReturn(firstPage)
                .thenReturn(secondPage);

        RecordingIndex index = new RecordingIndex();
        long count = source.backfill(index);

        assertThat(count).isEqualTo(2L);
        assertThat(index.upserts).extracting(SearchDocumentUpsert::title)
                .containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void backfill_isIdempotent_rePushesSameKeys() {
        Organisation org = Organisation.create("CRDB Bank", OrganisationType.PRIVATE_COMPANY);
        org.changeStatus(OrganisationStatus.ACTIVE);
        org.setVerified(true);
        when(organisationRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(org), PageRequest.of(0, 200), 1));

        RecordingIndex index = new RecordingIndex();
        source.backfill(index);
        source.backfill(index);

        // Two runs push the same single key twice — the upsert is keyed on (ORGANISATION, publicId), so the
        // distinct key set is unchanged across runs (idempotency: a re-run is an in-place update, never a new row).
        assertThat(index.upserts).hasSize(2);
        assertThat(index.upserts).extracting(SearchDocumentUpsert::entityPublicId)
                .containsExactly(org.getPublicId(), org.getPublicId());
        assertThat(index.upserts.stream().map(SearchDocumentUpsert::entityPublicId).distinct().toList())
                .containsExactly(org.getPublicId());
    }

    @Test
    void backfill_emptyCorpus_isACleanNoOp() {
        when(organisationRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        RecordingIndex index = new RecordingIndex();
        long count = source.backfill(index);

        assertThat(count).isZero();
        assertThat(index.upserts).isEmpty();
    }
}
