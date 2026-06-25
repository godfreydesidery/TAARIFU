package com.taarifu.communications.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.outbox.DomainEventHandler;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.communications.domain.model.DeviceToken;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.repository.DeviceTokenRepository;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The COMMUNICATIONS erasure handler — severs a subject's reachability data on a right-to-erasure request
 * (PRD §18, §25.1, UC-A17/UC-S09; ADR-0016 §5, ADR-0014; the {@link ErasureRequested} fan-out).
 *
 * <p>Responsibility: the asynchronous consumer the {@code OutboxRelay} delivers an {@link ErasureRequested}
 * event to for <b>this module's share</b> of the subject's personal data. The privacy fan-out deliberately
 * does not touch communications — this handler owns it. For the named subject it erases the two pieces of
 * <b>routing PII</b> communications holds about a person:</p>
 * <ul>
 *   <li><b>device tokens</b> — every live {@link DeviceToken} for the subject (FCM/APNs registration tokens
 *       are sensitive routing credentials, PRD §18): so after erasure no push can ever reach a former
 *       device, and the registry never resurfaces the subject as reachable;</li>
 *   <li><b>notification preferences</b> — every live {@link NotificationPreference} for the subject: the
 *       per-(type,channel) opt-ins/quiet-hours/language are about the person and carry no civic-record value
 *       to keep, so they are removed outright (a future re-signup starts from defaults).</li>
 * </ul>
 *
 * <p>Both are removed by <b>soft-delete</b> — consistent with the rest of this module (unregister/unfollow
 * are soft-deletes, PRD §9): the {@code @SQLRestriction("deleted = false")} on each entity means a
 * soft-deleted row is never read again by the push fan-out or the dispatcher, so reachability is fully
 * severed, while the deletion itself stays auditable. The deletion is attributed to the subject (a DSR is
 * self-initiated).</p>
 *
 * <p><b>What this handler does NOT touch:</b> existing {@code notification} delivery rows are the delivery
 * <i>ledger</i> (keyed by recipient {@code UUID}, body is a non-PII {@code payloadRef} only — never inline
 * content); de-identifying that history is out of scope for this routing-data erasure (the recipient ref
 * de-identification, if required, is a separate increment). {@code subscription} (follow) edges are civic
 * interest, not contact PII, and are likewise left to the wider de-identification policy. This handler's
 * remit is precisely the contact/routing surface: tokens + preferences.</p>
 *
 * <p><b>Idempotent (at-least-once, ADR-0014 §3):</b> the relay may redeliver the same {@code eventId}. The
 * handler reads only <i>live</i> rows ({@code @SQLRestriction}); a redelivery finds none and is a clean
 * no-op (no second mutation, no error). The effect is therefore idempotent by construction — no per-event
 * dedup table needed.</p>
 *
 * <p><b>🔒 Boundary/PII (ADR-0013):</b> the event payload carries ids only; the handler reads no PII from it
 * and never calls back into the producing (privacy) module — it operates solely on this module's own
 * tables, keyed by {@code subjectPublicId}. It <b>never logs a token string</b> (a secret) — log lines carry
 * the subject reference and counts only (PRD §18, CLAUDE.md §12). Runs in its own transaction so the severing
 * commits as a unit and a fault here fails only this event row (retry → DLQ), never a sibling event.</p>
 */
@Component
public class CommunicationsErasureHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CommunicationsErasureHandler.class);

    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param deviceTokenRepository the subject's live device tokens (revoked on erasure).
     * @param preferenceRepository  the subject's live notification preferences (removed on erasure).
     * @param objectMapper          shared Jackson mapper; converts the relay's tree payload back into the
     *                              typed {@link ErasureRequested} record (the relay is payload-agnostic).
     */
    public CommunicationsErasureHandler(DeviceTokenRepository deviceTokenRepository,
                                        NotificationPreferenceRepository preferenceRepository,
                                        ObjectMapper objectMapper) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.preferenceRepository = preferenceRepository;
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
     * Handles a delivered erasure event: revoke the subject's device tokens and remove their preferences,
     * idempotently.
     *
     * <p>Runs in its <b>own</b> transaction ({@link Propagation#REQUIRES_NEW}) so the severing commits (or
     * rolls back) as a unit and is isolated from the relay's batch transaction — a fault here fails only this
     * row (retry → DLQ), never a sibling event. A subject with no live tokens/preferences (none registered, or
     * an earlier delivery already erased them) is a clean idempotent no-op.</p>
     *
     * @param event the delivered envelope; {@code event.eventId()} is the at-least-once idempotency key (the
     *              effect is also idempotent by reading only live rows — a redelivery finds none).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(EventEnvelope<?> event) {
        ErasureRequested payload = objectMapper.convertValue(event.payload(), ErasureRequested.class);
        UUID subject = payload.subjectPublicId();

        int tokens = revokeDeviceTokens(subject);
        int prefs = removePreferences(subject);

        if (tokens == 0 && prefs == 0) {
            // Nothing live for the subject — already erased by an earlier delivery, or never had any (no-op).
            log.debug("Communications erasure: nothing to erase for subject (eventId={})", event.eventId());
            return;
        }
        // Counts + subject reference only — NEVER a token string or any PII (PRD §18).
        log.info("Communications erased for subject reference: tokens={}, preferences={} (eventId={})",
                tokens, prefs, event.eventId());
    }

    /**
     * Soft-deletes every live device token for the subject (revokes push reachability).
     *
     * @param subject the erasing subject's public id.
     * @return the number of tokens revoked (0 → none were live).
     */
    private int revokeDeviceTokens(UUID subject) {
        List<DeviceToken> tokens = deviceTokenRepository.findByProfileId(subject);
        for (DeviceToken token : tokens) {
            token.markDeleted(subject); // self-initiated DSR — attribute the revoke to the subject.
        }
        deviceTokenRepository.saveAll(tokens);
        return tokens.size();
    }

    /**
     * Soft-deletes every live notification preference for the subject.
     *
     * @param subject the erasing subject's public id.
     * @return the number of preferences removed (0 → none were live).
     */
    private int removePreferences(UUID subject) {
        List<NotificationPreference> prefs = preferenceRepository.findByProfileId(subject);
        for (NotificationPreference pref : prefs) {
            pref.markDeleted(subject);
        }
        preferenceRepository.saveAll(prefs);
        return prefs.size();
    }
}
