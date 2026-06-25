package com.taarifu.search.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.api.event.ModerationSanctionApplied;
import com.taarifu.moderation.api.event.SanctionType;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchVisibilityMaintenanceHandler} — the outbox-driven visibility maintenance that
 * pulls a suspended author's content out of public discovery (ADR-0017 §3; ADR-0014 §4).
 *
 * <p>Responsibility: prove that a {@code SUSPEND} hides the author's rows by their opaque account id, that a
 * {@code VERIFY_REQUEST} is a no-op, and that the handler registers on the right taxonomy key. The
 * hide-by-author update is itself naturally idempotent (it targets only still-{@code PUBLIC} rows), so a
 * redelivered event re-running it is safe — asserted indirectly by the single delegation to the repository.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchVisibilityMaintenanceHandlerTest {

    @Mock
    private SearchDocumentRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private SearchVisibilityMaintenanceHandler handler() {
        return new SearchVisibilityMaintenanceHandler(repository, objectMapper);
    }

    private final UUID accountId = UUID.randomUUID();

    private EventEnvelope<ModerationSanctionApplied> envelope(SanctionType type) {
        ModerationSanctionApplied payload = new ModerationSanctionApplied(accountId, type, Instant.now());
        return new EventEnvelope<>(UUID.randomUUID(), ModerationEventTypes.MODERATION_SANCTION_APPLIED,
                ModerationEventTypes.AGGREGATE_MODERATION_ITEM, accountId, payload, Instant.now());
    }

    @Test
    void registersOnTheModerationSanctionTaxonomyKey() {
        assertThat(handler().handledEventTypes())
                .containsExactly(ModerationEventTypes.MODERATION_SANCTION_APPLIED);
    }

    @Test
    void suspend_hidesTheAuthorsPublicDocuments() {
        when(repository.hideByAuthor(accountId)).thenReturn(3);

        handler().handle(envelope(SanctionType.SUSPEND));

        verify(repository).hideByAuthor(accountId);
    }

    @Test
    void verifyRequest_isANoOp_doesNotHideAnything() {
        handler().handle(envelope(SanctionType.VERIFY_REQUEST));

        // A verify-request fences the account but does not pull standing content from discovery.
        verify(repository, never()).hideByAuthor(any());
    }
}
