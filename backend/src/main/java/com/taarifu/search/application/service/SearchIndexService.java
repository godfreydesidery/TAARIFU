package com.taarifu.search.application.service;

import com.taarifu.search.api.SearchIndexApi;
import com.taarifu.search.api.dto.SearchDocumentUpsert;
import com.taarifu.search.domain.model.SearchDocument;
import com.taarifu.search.domain.model.enums.SearchEntityType;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The {@link SearchIndexApi} implementation — the write side of the discovery index that owning modules push to
 * (ADR-0017 §1). It owns the upsert/remove transaction; the {@code search_vector} is computed by the database
 * (a generated column — ADR-0017 §2), so this service only persists the public projection fields.
 *
 * <p>Responsibility: turn an owner's {@link SearchDocumentUpsert} into an idempotent insert-or-update of the
 * single live {@code search_document} row for {@code (entityType, entityPublicId)}, and soft-remove a row on
 * {@link #remove}. Being the in-process impl of the published port, the caller (any owning module) injects the
 * {@link SearchIndexApi} interface, never this class (ADR-0013 §1).</p>
 *
 * <p><b>Idempotency:</b> {@link #upsert} looks up the live row by source key and updates it in place if present
 * (never a duplicate); the live-scoped unique index {@code ux_search_document_source_live} is the DB backstop.
 * {@link #remove} no-ops when nothing is indexed.</p>
 *
 * <p><b>🔒 PII discipline:</b> the projection carries public display data + opaque ids only (enforced by the
 * caller per {@link SearchDocumentUpsert}); this service logs the entity type + source public id + a boolean
 * outcome only — never the title, snippet, or author id (PRD §18, CLAUDE.md §12).</p>
 */
@Service
public class SearchIndexService implements SearchIndexApi {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    private final SearchDocumentRepository repository;

    /**
     * @param repository the {@code search_document} persistence port.
     */
    public SearchIndexService(SearchDocumentRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inserts a new projection row, or updates the existing live one in place — keeping one row per source
     * (idempotent). The generated {@code search_vector} is recomputed by the database from the new
     * title/keywords/snippets on commit.</p>
     */
    @Override
    @Transactional
    public void upsert(SearchDocumentUpsert upsert) {
        SearchDocument doc = repository.findLiveBySource(upsert.entityType(), upsert.entityPublicId())
                .map(existing -> {
                    existing.applyUpdate(upsert.title(), upsert.snippetSw(), upsert.snippetEn(),
                            upsert.keywords(), upsert.areaId(), upsert.categoryId(),
                            upsert.visibility(), upsert.authoredByAccountId());
                    return existing;
                })
                .orElseGet(() -> SearchDocument.project(upsert.entityType(), upsert.entityPublicId(),
                        upsert.title(), upsert.snippetSw(), upsert.snippetEn(), upsert.keywords(),
                        upsert.areaId(), upsert.categoryId(), upsert.visibility(),
                        upsert.authoredByAccountId()));
        repository.save(doc);
        log.debug("Indexed {} {} (visibility={})",
                upsert.entityType(), upsert.entityPublicId(), upsert.visibility());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Soft-deletes the live projection row (history is retained per BaseEntity soft-delete); a no-op when the
     * source is not indexed. The deleting actor is left {@code null} (system-initiated index maintenance).</p>
     */
    @Override
    @Transactional
    public void remove(SearchEntityType entityType, UUID entityPublicId) {
        repository.findLiveBySource(entityType, entityPublicId).ifPresent(doc -> {
            doc.markDeleted(null);
            repository.save(doc);
            log.debug("Removed {} {} from discovery index", entityType, entityPublicId);
        });
    }
}
