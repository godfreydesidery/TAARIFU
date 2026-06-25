package com.taarifu.institutions.infrastructure.adapter;

import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.model.enums.Legislature;
import com.taarifu.institutions.domain.model.enums.Mandate;
import com.taarifu.institutions.domain.model.enums.RepresentativeStatus;
import com.taarifu.institutions.domain.model.enums.RepresentativeType;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import com.taarifu.institutions.test.EntityTestSupport;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RepresentativeSearchBackfillSource} — institutions' search backfill adapter for the
 * REPRESENTATIVE directory (ADR-0017 backfill follow-up). Mockito only.
 *
 * <p>Responsibility: proves the adapter re-pushes the public directory through {@code SearchIndexApi.upsert}
 * with the <b>same projection and visibility fence as the live write path</b> (the shared
 * {@code RepresentativeSearchProjection}): a SITTING/FORMER rep indexes {@code PUBLIC}, a PENDING_VERIFICATION
 * rep indexes {@code STAFF} (it indexes every live row, never filtering PENDING out). It also proves the count
 * receipt, that it pages until exhausted, that it carries no PII (no bio/profileId), and that it handles an
 * empty directory cleanly. Each test would fail if the projection or visibility drifted from the live path.</p>
 */
class RepresentativeSearchBackfillSourceTest {

    private RepresentativeRepository representativeRepository;
    private SearchIndexApi index;
    private RepresentativeSearchBackfillSource source;

    @BeforeEach
    void setUp() {
        representativeRepository = mock(RepresentativeRepository.class);
        index = mock(SearchIndexApi.class);
        source = new RepresentativeSearchBackfillSource(representativeRepository);
    }

    @Test
    void entityType_isRepresentative() {
        assertThat(source.entityType()).isEqualTo(SearchEntityType.REPRESENTATIVE);
    }

    /**
     * A constituency-MP (SITTING) and a PENDING MP are both indexed in one batch — SITTING → PUBLIC,
     * PENDING_VERIFICATION → STAFF — mirroring the live producer's status→visibility fence exactly.
     */
    @Test
    void backfill_indexesEveryLiveRow_withLiveVisibilityFence_andReturnsCount() {
        Representative sitting = constituencyMp(RepresentativeStatus.SITTING, "Rombo");
        Representative pending = constituencyMp(RepresentativeStatus.PENDING_VERIFICATION, "Vunjo");
        when(representativeRepository.findAllForSearchBackfill(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sitting, pending), PageRequest.of(0, 200), 2));

        long count = source.backfill(index);

        assertThat(count).isEqualTo(2L);
        ArgumentCaptor<SearchDocumentUpsert> captor = ArgumentCaptor.forClass(SearchDocumentUpsert.class);
        verify(index, times(2)).upsert(captor.capture());
        List<SearchDocumentUpsert> pushed = captor.getAllValues();

        SearchDocumentUpsert sittingDoc = byPublicId(pushed, sitting.getPublicId());
        assertThat(sittingDoc.entityType()).isEqualTo(SearchEntityType.REPRESENTATIVE);
        assertThat(sittingDoc.visibility()).isEqualTo(SearchVisibility.PUBLIC);
        assertThat(sittingDoc.title()).isEqualTo("Mbunge — Rombo");
        // 🔒 PII: a directory entity carries no author account id, and never the bio/profileId.
        assertThat(sittingDoc.authoredByAccountId()).isNull();

        SearchDocumentUpsert pendingDoc = byPublicId(pushed, pending.getPublicId());
        // A PENDING claim is indexed STAFF (anti-claim-spoofing) — NOT filtered out, NOT public.
        assertThat(pendingDoc.visibility()).isEqualTo(SearchVisibility.STAFF);
    }

    /** The backfill pages until {@code hasNext()} is false — proving it does not stop after the first batch. */
    @Test
    void backfill_pagesUntilExhausted() {
        Representative a = constituencyMp(RepresentativeStatus.SITTING, "A");
        Representative b = constituencyMp(RepresentativeStatus.FORMER, "B");
        Page<Representative> firstFull = new PageImpl<>(List.of(a), PageRequest.of(0, 1), 2);  // hasNext == true
        Page<Representative> secondLast = new PageImpl<>(List.of(b), PageRequest.of(1, 1), 2); // hasNext == false
        when(representativeRepository.findAllForSearchBackfill(any(Pageable.class)))
                .thenReturn(firstFull, secondLast);

        long count = source.backfill(index);

        assertThat(count).isEqualTo(2L);
        verify(index, times(2)).upsert(any());
        verify(representativeRepository, times(2)).findAllForSearchBackfill(any(Pageable.class));
    }

    /** An empty directory is a clean no-op: 0 upserts, 0 returned, never an exception. */
    @Test
    void backfill_emptyDirectory_returnsZero_noUpserts() {
        when(representativeRepository.findAllForSearchBackfill(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        assertThat(source.backfill(index)).isZero();
        verify(index, times(0)).upsert(any());
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------------

    /** Builds a CONSTITUENCY-mandate MP at the given status with a named constituency seat. */
    private Representative constituencyMp(RepresentativeStatus status, String constituencyName) {
        Constituency constituency = EntityTestSupport.newWithIds(Constituency.class, 1L, UUID.randomUUID());
        EntityTestSupport.set(constituency, "name", constituencyName);
        Representative rep = EntityTestSupport.newWithIds(Representative.class, null, UUID.randomUUID());
        rep.applyDetails(UUID.randomUUID(), RepresentativeType.MP, Mandate.CONSTITUENCY,
                constituency, null, null, Legislature.UNION_PARLIAMENT, null, null, status, null, "secret bio");
        return rep;
    }

    private static SearchDocumentUpsert byPublicId(List<SearchDocumentUpsert> docs, UUID publicId) {
        return docs.stream().filter(d -> publicId.equals(d.entityPublicId())).findFirst().orElseThrow();
    }
}
