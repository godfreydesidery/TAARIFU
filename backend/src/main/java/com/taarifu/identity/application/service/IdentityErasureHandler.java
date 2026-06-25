package com.taarifu.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * The IDENTITY erasure handler — the most sensitive severing on the platform (PRD §18, §25.1, UC-A17/UC-S09;
 * ADR-0016 §5, ADR-0014).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers an {@link ErasureRequested}
 * event to. For the named subject it (1) crypto-shreds the encrypted national/voter ID ({@code idNo}
 * ciphertext + the {@code idHash} blind index), (2) nulls the rest of the profile PII and tombstones the
 * names, (3) tombstones the account (phone → non-reusable token, credentials/contacts nulled, status
 * DISABLED), (4) deletes the private {@code ProfileLocation}s (PRD §25.1: locations are <i>deleted</i> on
 * erasure), and (5) appends an {@link AuditEventType#IDENTITY_ERASED} tombstone to the immutable audit log.
 * The de-identified <b>civic record</b> (reports/signatures/ratings owned by other modules) is untouched here —
 * each owning module de-identifies its own reporter/author reference via its own erasure handler (CENTRAL
 * NEED); identity owns only the person.</p>
 *
 * <p><b>The hash-chain is never broken</b> (PRD §25.1, ADR-0011): erasure does <b>not</b> mutate or delete any
 * existing audit row — it <b>appends</b> a new {@code IDENTITY_ERASED} tombstone. The audit writer extends the
 * tamper-evident chain; history stays intact and verifiable.</p>
 *
 * <p><b>One-account permanence (D15/§6.4):</b> the account row is kept (not deleted) so the de-identified civic
 * record stays referentially intact and the phone tombstone keeps the unique index without freeing a real
 * number for a second signup.</p>
 *
 * <p><b>Idempotent (at-least-once, ADR-0014 §3):</b> the relay may redeliver the same {@code eventId}. The
 * handler no-ops if the profile is already anonymised ({@link Profile#isAnonymised()}) — a redelivery makes no
 * second tombstone audit and no second mutation.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the event payload carries ids only; the handler reads no PII from it and
 * never calls back into the producing (privacy) module — it operates solely on identity's own aggregate. The
 * plaintext ID is never read, decrypted, or logged at any step (S-4); logging is by subject reference only.</p>
 */
@Component
public class IdentityErasureHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityErasureHandler.class);

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;
    private final AuditEventService audit;
    private final ObjectMapper objectMapper;

    /**
     * @param userRepository            account lookup + tombstone target.
     * @param profileRepository         profile lookup + crypto-shred/anonymise target.
     * @param profileLocationRepository the subject's private locations (deleted on erasure — §25.1).
     * @param audit                     append-only audit writer (writes the {@code IDENTITY_ERASED} tombstone —
     *                                  never mutates the hash-chain, L-1).
     * @param objectMapper              shared Jackson mapper; converts the relay's tree payload back into the
     *                                  typed {@link ErasureRequested} record (the relay is payload-agnostic).
     */
    public IdentityErasureHandler(UserRepository userRepository,
                                  ProfileRepository profileRepository,
                                  ProfileLocationRepository profileLocationRepository,
                                  AuditEventService audit,
                                  ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * @return the single taxonomy key {@link ErasureRequested#EVENT_TYPE} this handler consumes.
     */
    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ErasureRequested.EVENT_TYPE);
    }

    /**
     * Handles a delivered erasure event: crypto-shred + tombstone the identity, idempotently.
     *
     * <p>Runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so the severing commits (or
     * rolls back) as a unit and is isolated from the relay's batch transaction — a fault here fails only this
     * row (retry → DLQ), never a sibling event. A missing account is treated as already-erased (idempotent
     * no-op): the subject may have been erased by an earlier delivery or never existed.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the at-least-once idempotency key (the
     *              effect is also made idempotent by the {@link Profile#isAnonymised()} guard).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope<?> event) {
        ErasureRequested payload = objectMapper.convertValue(event.payload(), ErasureRequested.class);
        UUID subjectPublicId = payload.subjectPublicId();

        User user = userRepository.findByPublicId(subjectPublicId).orElse(null);
        if (user == null) {
            // Already erased by an earlier delivery, or never existed — idempotent no-op (no audit spam).
            log.debug("Erasure event for unknown/already-erased subject; no-op (eventId={})", event.eventId());
            return;
        }

        Profile profile = profileRepository.findByUser(user).orElse(null);
        if (profile != null && profile.isAnonymised()) {
            // Redelivery of an already-completed erasure — idempotent no-op (ADR-0014 §3).
            log.debug("Erasure already applied for subject; no-op (eventId={})", event.eventId());
            return;
        }

        // 1+2. Crypto-shred the national/voter ID and sever the remaining profile PII (keep the row).
        if (profile != null) {
            profile.anonymise(tombstoneLabel(subjectPublicId));
        }
        // 3. Tombstone the account: phone → non-reusable token, credentials/contacts nulled, DISABLED.
        user.tombstone(tombstonePhone());
        // 4. Delete the private ProfileLocations (PRD §25.1: locations are deleted on erasure).
        if (profile != null) {
            profileLocationRepository.deleteAll(profileLocationRepository.findByProfile(profile));
        }

        // 5. Append the IDENTITY_ERASED tombstone — the audit hash-chain is EXTENDED, never broken (§25.1).
        // References only: actor = subject (self-initiated erasure), subject = the account; no PII.
        audit.record(AuditEvent.Builder
                .of(AuditEventType.IDENTITY_ERASED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId)
                .reason("DSR:" + payload.dsrPublicId())
                .build());

        log.info("Identity erased (crypto-shred + tombstone) for subject reference (eventId={})", event.eventId());
    }

    /**
     * The {@code anonymized_user_<short>} display label that replaces the subject's names (§25.1 tombstone).
     * Derived from the subject's own public id so it is stable across a redelivery but carries no PII.
     */
    private static String tombstoneLabel(UUID subjectPublicId) {
        return "anonymized_user_" + subjectPublicId.toString().substring(0, 8);
    }

    /**
     * A unique, non-reusable phone tombstone token satisfying the not-null unique {@code phone} index without
     * being a real, re-registrable number (one-account permanence — D15/§6.4). A fresh UUID guarantees
     * uniqueness even across two erased accounts.
     */
    private static String tombstonePhone() {
        return "erased:" + UUID.randomUUID();
    }
}
