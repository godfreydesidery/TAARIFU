package com.taarifu.search.application.service;

import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.SearchDocument;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.model.enums.SearchVisibility;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchIndexService} — the idempotent <b>upsert</b> + <b>remove</b> of the discovery
 * index that owning modules push to (ADR-0017 §1).
 *
 * <p>Responsibility: prove that re-pushing the same source updates its single live row in place (never a
 * duplicate), that a first push inserts a new row, and that removing an absent source is a clean no-op. Each
 * test fails if the idempotency lookup were dropped.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

    @Mock
    private SearchDocumentRepository repository;

    private SearchIndexService service() {
        return new SearchIndexService(repository);
    }

    private final UUID sourceId = UUID.randomUUID();

    private SearchDocumentUpsert upsert(String title, SearchVisibility visibility) {
        return new SearchDocumentUpsert(SearchEntityType.ORGANISATION, sourceId, title,
                "huduma za maji", "water services", "maji,DAWASA", null, null, visibility, null);
    }

    @Test
    void firstPush_insertsANewRow() {
        when(repository.findLiveBySource(SearchEntityType.ORGANISATION, sourceId))
                .thenReturn(Optional.empty());

        service().upsert(upsert("DAWASA", SearchVisibility.PUBLIC));

        ArgumentCaptor<SearchDocument> captor = ArgumentCaptor.forClass(SearchDocument.class);
        verify(repository).save(captor.capture());
        SearchDocument saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo(SearchEntityType.ORGANISATION);
        assertThat(saved.getEntityPublicId()).isEqualTo(sourceId);
        assertThat(saved.getTitle()).isEqualTo("DAWASA");
        assertThat(saved.getVisibility()).isEqualTo(SearchVisibility.PUBLIC);
    }

    @Test
    void rePush_updatesTheExistingRowInPlace_notADuplicate() {
        SearchDocument existing = SearchDocument.project(SearchEntityType.ORGANISATION, sourceId,
                "Old name", null, null, null, null, null, SearchVisibility.PUBLIC, null);
        when(repository.findLiveBySource(SearchEntityType.ORGANISATION, sourceId))
                .thenReturn(Optional.of(existing));

        service().upsert(upsert("New name", SearchVisibility.STAFF));

        // The same instance is updated and saved — proving an upsert, not a second insert.
        ArgumentCaptor<SearchDocument> captor = ArgumentCaptor.forClass(SearchDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(existing.getTitle()).isEqualTo("New name");
        assertThat(existing.getVisibility()).isEqualTo(SearchVisibility.STAFF);
    }

    @Test
    void remove_softDeletesTheLiveRow() {
        SearchDocument existing = SearchDocument.project(SearchEntityType.ANNOUNCEMENT, sourceId,
                "Tangazo", null, null, null, null, null, SearchVisibility.PUBLIC, null);
        when(repository.findLiveBySource(SearchEntityType.ANNOUNCEMENT, sourceId))
                .thenReturn(Optional.of(existing));

        service().remove(SearchEntityType.ANNOUNCEMENT, sourceId);

        assertThat(existing.isDeleted()).isTrue();
        verify(repository).save(existing);
    }

    @Test
    void remove_absentSource_isANoOp() {
        when(repository.findLiveBySource(SearchEntityType.ANNOUNCEMENT, sourceId))
                .thenReturn(Optional.empty());

        service().remove(SearchEntityType.ANNOUNCEMENT, sourceId);

        verify(repository, never()).save(any());
    }
}
