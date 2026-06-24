package com.taarifu.identity.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.api.event.ModerationSanctionApplied;
import com.taarifu.moderation.api.event.SanctionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Identity's outbox consumer for the moderation <b>account-sanction</b> event (gap A5; PRD §18, UC-H02;
 * ADR-0013 §2 "moderation takedown → owner effect is event-driven"; ADR-0014 §4).
 *
 * <p>Responsibility: register for the {@link ModerationEventTypes#MODERATION_SANCTION_APPLIED} taxonomy key
 * and apply the account-state change identity <b>owns</b> — moderation never reaches into identity
 * (ARCHITECTURE.md §3.2). When a moderator records a {@code SUSPEND} action, this handler suspends the
 * author's account ({@code UserStatus.SUSPENDED}) so it can no longer authenticate/act until an admin
 * reinstates it (the login path already refuses a non-{@code ACTIVE} account; short access-token TTL +
 * refresh rotation bound any live session — see {@link User#suspend()}). The decision is mirrored to the
 * immutable audit store as {@link AuditEventType#USER_SUSPENDED} with the deciding moderator as actor.</p>
 *
 * <p><b>WHY a published-api import (not a JsonNode reach-around):</b> the producer (moderation) publishes the
 * event contract in its {@code moderation.api.event} package precisely so a sibling consumer may depend on it
 * — a sanctioned cross-module {@code ..api..} reference (ADR-0013 §3), the same shape as the analytics handler
 * importing {@code CivicActivityRecorded}. Identity depends only on {@code moderation.api.event}; it never
 * imports moderation's {@code domain}/{@code infrastructure} (the boundary holds, {@code ModuleBoundaryTest}).</p>
 *
 * <h3>Idempotency (at-least-once delivery — ADR-0014 §3)</h3>
 * The relay can redeliver the same {@code eventId}. The applied effect is <b>naturally idempotent</b>: the
 * account-state transition is a conditional write — if the account is already {@link UserStatus#SUSPENDED}
 * this handler is a no-op and writes <b>no</b> audit row, so a redelivery never double-suspends or
 * double-audits. No per-event dedup table is needed (the preferred ADR-0014 §3 pattern where the schema/state
 * already enforces the invariant).
 *
 * <p><b>🔒 No PII (PRD §18, ADR-0014 §1):</b> the event carries only the author's opaque <b>account</b> public
 * id and the controlled-vocabulary {@link SanctionType}; this handler reads no name/phone/ID and logs only the
 * {@code eventId}/sanction type/an unknown-account marker — never the account id at INFO and never any PII.</p>
 */
@Component
public class ModerationSanctionHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ModerationSanctionHandler.class);

    private final UserRepository userRepository;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;

    /**
     * @param userRepository the account store — resolves the sanctioned account by its public id and persists
     *                       the {@code SUSPENDED} transition (the single writer of {@code app_user} status here).
     * @param audit          append-only audit writer — mirrors the applied sanction into the unified store
     *                       ({@link AuditEventType#USER_SUSPENDED}); references/codes only, never PII (L-1).
     * @param objectMapper   the shared Jackson mapper — deserialises the relay's {@link JsonNode} payload into
     *                       the published {@link ModerationSanctionApplied} record (ids/enums only).
     */
    public ModerationSanctionHandler(UserRepository userRepository,
                                     AuditEventService audit,
                                     ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} Registers for the single moderation {@code MODERATION_SANCTION_APPLIED} taxonomy key. */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ModerationEventTypes.MODERATION_SANCTION_APPLIED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies the account sanction idempotently. A {@code SUSPEND} suspends an {@code ACTIVE} account; an
     * already-{@code SUSPENDED}/{@code DISABLED} account is a no-op (no double-audit). A {@code VERIFY_REQUEST}
     * is recognised but applies no account-state change in this increment (identity has no verify-request gate
     * flag yet — adding one is a schema change out of this analytics+consume scope); it is logged so the gap is
     * visible, never silently mis-applied as a suspension. An unknown account id is a no-op (the author may have
     * been erased/anonymised between the action and delivery — §25.1); it does not fail the row.</p>
     */
    @Override
    @Transactional
    public void handle(EventEnvelope<?> event) {
        ModerationSanctionApplied sanction = deserialise(event.payload());
        UUID accountId = sanction.subjectAccountId();
        SanctionType type = sanction.sanctionType();

        if (accountId == null || type == null) {
            // A malformed sanction (missing account/type) cannot be applied; surface it so the relay records
            // the failure rather than silently dropping a state-changing event.
            throw new IllegalStateException("MODERATION_SANCTION_APPLIED missing account or sanction type, eventId="
                    + event.eventId());
        }

        User user = userRepository.findByPublicId(accountId).orElse(null);
        if (user == null) {
            // Forward-tolerant no-op: the author may have been anonymised/erased between the action and delivery
            // (§25.1), or the id is from a tenant this build does not know. Drop rather than DLQ the row.
            log.info("MODERATION_SANCTION_APPLIED for unknown account (eventId={}, type={}); no-op",
                    event.eventId(), type);
            return;
        }

        switch (type) {
            case SUSPEND -> applySuspension(user, event.eventId());
            case VERIFY_REQUEST -> log.info(
                    "MODERATION_SANCTION_APPLIED VERIFY_REQUEST (eventId={}); no account-state gate modelled yet",
                    event.eventId());
        }
    }

    /**
     * Suspends an account idempotently: a no-op (no state change, no audit) if it is not currently
     * {@link UserStatus#ACTIVE}, so a redelivered event never double-suspends or double-audits (ADR-0014 §3).
     * The applied suspension is mirrored to the audit store with the system as actor (this is a system-applied
     * consequence of a moderator action — the deciding moderator is on the moderation_action row already).
     *
     * @param user    the account to suspend.
     * @param eventId the outbox event id (the idempotency key; recorded as the audit detail reference, not PII).
     */
    private void applySuspension(User user, UUID eventId) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            // Already suspended/disabled/pending — the consequence is satisfied; do not re-audit (idempotent).
            return;
        }
        user.suspend();
        userRepository.save(user);
        audit.record(AuditEvent.Builder
                .of(AuditEventType.USER_SUSPENDED, AuditOutcome.SUCCESS)
                .subject(user.getPublicId())
                .reason("MODERATION_SANCTION")
                .detailRef(eventId == null ? null : eventId.toString())
                .build());
    }

    /**
     * Deserialises the relay-delivered payload (a Jackson {@link JsonNode} tree — the relay is payload-agnostic,
     * ADR-0014 §3) into the published {@link ModerationSanctionApplied} record (ids/enums only; no PII).
     *
     * @param payload the envelope payload as delivered (a {@code JsonNode} from the persisted {@code jsonb}).
     * @return the typed sanction record.
     * @throws IllegalStateException if the payload cannot be read as {@link ModerationSanctionApplied} (a
     *                               non-retryable data defect — surfaced so the relay records the failure).
     */
    private ModerationSanctionApplied deserialise(Object payload) {
        try {
            if (payload instanceof JsonNode node) {
                return objectMapper.treeToValue(node, ModerationSanctionApplied.class);
            }
            // Defensive: an in-process producer test may hand the concrete record straight through.
            return objectMapper.convertValue(payload, ModerationSanctionApplied.class);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(
                    "MODERATION_SANCTION_APPLIED payload is not a valid ModerationSanctionApplied", ex);
        }
    }
}
