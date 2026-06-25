package com.taarifu.search.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.api.event.ModerationSanctionApplied;
import com.taarifu.moderation.api.event.SanctionType;
import com.taarifu.search.domain.repository.SearchDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Outbox {@link DomainEventHandler} that maintains discovery <b>visibility</b> when a content author is
 * sanctioned — it hides a suspended author's indexed documents out of public discovery (ADR-0017 §3; ADR-0014 §4).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers a
 * {@link ModerationSanctionApplied} event to. On a {@link SanctionType#SUSPEND}, it bulk-updates every live
 * {@code search_document} authored by that account from {@code PUBLIC} to {@code STAFF} visibility, so a
 * suspended author's content drops out of every guest/citizen search immediately — without search reaching into
 * moderation or identity (the event is the sanctioned async cross-module contract — ADR-0013 §2/§3). This is the
 * proof that the outbox seam works for search; the broader per-document {@code ContentRemoved} takedown is a
 * documented follow-up (that event does not exist yet — ADR-0017 §3).</p>
 *
 * <p><b>WHY only {@code SUSPEND} (not {@code VERIFY_REQUEST}):</b> a verify-request fences the account until it
 * re-verifies but does not remove its standing content from the public record; only a suspension warrants
 * pulling the author's content out of discovery. A {@code VERIFY_REQUEST} event is therefore a no-op here.</p>
 *
 * <p><b>Idempotency</b> (at-least-once delivery, ADR-0014 §3): the update is naturally idempotent — it targets
 * only rows still {@code PUBLIC}, so a redelivered event re-runs the same UPDATE and matches zero additional
 * rows (no double effect, no dedup table needed). It also makes no assumption about ordering: an
 * upsert that re-publicises the author's content after a later re-instatement is a separate flow.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the payload carries the author's opaque <b>account</b> id + the sanction
 * enum only; the handler reads no PII, and it does NOT call back into moderation/identity — it acts solely on
 * this module's own {@code search_document} rows keyed by the opaque account id.</p>
 */
@Component
public class SearchVisibilityMaintenanceHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SearchVisibilityMaintenanceHandler.class);

    private final SearchDocumentRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * @param repository   the {@code search_document} persistence port (bulk visibility update).
     * @param objectMapper shared Jackson mapper; converts the relay's tree payload to the typed event record
     *                     (the relay is payload-agnostic and hands handlers a tree — ADR-0014 §4).
     */
    public SearchVisibilityMaintenanceHandler(SearchDocumentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * @return the single taxonomy key {@link ModerationEventTypes#MODERATION_SANCTION_APPLIED} this handler
     *         consumes.
     */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ModerationEventTypes.MODERATION_SANCTION_APPLIED);
    }

    /**
     * Handles a delivered sanction event: on a {@code SUSPEND}, hide the author's public indexed content.
     *
     * @param event the delivered envelope; its {@code eventId} is the at-least-once idempotency key (the effect
     *              is naturally idempotent — see the type doc).
     */
    @Override
    @Transactional
    public void handle(EventEnvelope<?> event) {
        ModerationSanctionApplied sanction =
                objectMapper.convertValue(event.payload(), ModerationSanctionApplied.class);

        if (sanction.sanctionType() != SanctionType.SUSPEND) {
            // VERIFY_REQUEST does not pull standing content from discovery — no-op (idempotent by nature).
            return;
        }
        int hidden = repository.hideByAuthor(sanction.subjectAccountId());
        // Log the opaque account id + count only — never any PII (PRD §18).
        log.info("Suspension of account {} hid {} public search document(s) from discovery",
                sanction.subjectAccountId(), hidden);
    }
}
