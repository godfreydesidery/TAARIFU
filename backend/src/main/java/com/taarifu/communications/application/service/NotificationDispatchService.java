package com.taarifu.communications.application.service;

import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.communications.domain.model.Notification;
import com.taarifu.communications.domain.model.NotificationPreference;
import com.taarifu.communications.domain.model.enums.Channel;
import com.taarifu.communications.domain.model.enums.NotificationType;
import com.taarifu.communications.domain.port.EmailSender;
import com.taarifu.communications.domain.port.PushSender;
import com.taarifu.communications.domain.port.SmsGateway;
import com.taarifu.communications.domain.repository.NotificationPreferenceRepository;
import com.taarifu.communications.domain.repository.NotificationRepository;
import com.taarifu.identity.api.RecipientContactApi;
import com.taarifu.identity.api.dto.RecipientContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Preference-aware notification dispatch with channel selection and SMS fallback (PRD §13, M5, EI-3/5/6).
 *
 * <p>Responsibility: turns "notify this recipient about this event" into one or more
 * {@link Notification} rows, one per <b>selected</b> channel, then attempts delivery through the channel
 * ports. Channel selection respects the recipient's {@link NotificationPreference} (opt-in per
 * type/channel, quiet hours, language) and the channel-matrix defaults (PRD §13). The two civic-inclusion
 * invariants this service enforces:</p>
 * <ul>
 *   <li><b>FEED is always retained</b> — the durable in-app channel is never suppressed by quiet hours or
 *       a push/SMS failure, so an item is never lost (EI-5).</li>
 *   <li><b>PUSH degrades to SMS</b> — when push reports {@code noToken}, the dispatcher falls back to SMS
 *       (subject to the recipient's SMS opt-in), so a citizen with no smartphone still hears (US-5.1).</li>
 * </ul>
 *
 * <p><b>Idempotency</b> (DI4, PRD §13 "all sends idempotent"): every queued row carries a stable key
 * {@code type:channel:recipient:source}; a duplicate dispatch (outbox replay, retried webhook) finds the
 * existing row and does <b>not</b> re-create or re-send it. The unique index on {@code idempotency_key}
 * is the hard backstop.</p>
 *
 * <p><b>Always-on types</b> ({@code SYSTEM}, {@code MODERATION_OUTCOME}) ignore an opt-out — a citizen
 * cannot silence security/moderation notices (PRD §13).</p>
 *
 * <p><b>🔒 Recipient contact resolution & PII (ADR-0013 §1, §4; PRD §18, PDPA):</b> the SMS and email
 * channels must address a <i>real</i> destination, which only identity holds. This service resolves the
 * recipient's raw MSISDN/email once per dispatch through identity's published
 * {@link RecipientContactApi} — but <b>only</b> when a contact-bearing channel could actually fire (an SMS
 * or email row, or a PUSH that may degrade to SMS); a FEED/PUSH-only dispatch does no identity round-trip.
 * The resolved {@link RecipientContact} is treated as the most sensitive value on this path: it is handed
 * <b>straight to the masking {@code SmsGateway}/{@code EmailSender}</b> and is <b>never logged, never
 * persisted, and never placed in an event, feed item, or audit row</b> (S-4). A recipient with no usable
 * contact for a channel (no profile, an anonymised/tombstoned account, or an unverified/absent email) is a
 * <b>graceful skip</b> — the row is marked {@code FAILED} with a non-PII reason
 * ({@code NO_MSISDN_FOR_RECIPIENT}/{@code NO_EMAIL_FOR_RECIPIENT}) and the fan-out continues; a single
 * unreachable recipient never crashes the dispatch (EI-3/6).</p>
 *
 * <p>WHY this is synchronous-but-fail-soft here (not yet a worker): the transactional-outbox + worker
 * substrate is a later increment (ARCHITECTURE §8). This service is written so it can be moved behind the
 * bus unchanged — it never throws on a routine channel failure (it records {@code FAILED}/falls back), so
 * the caller's transaction (e.g. publishing the announcement) never rolls back on a provider outage
 * (DI3). It is now invoked from the {@code AnnouncementPublishedHandler} outbox consumer (ADR-0014 §5a),
 * which the {@code OutboxRelay} drives off the request thread.</p>
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /** Time zone for evaluating a recipient's quiet hours when no per-pref zone is modelled (EAT). */
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    /** Types that cannot be silenced by an opt-out (PRD §13 "always"). */
    private static final Set<NotificationType> ALWAYS_ON =
            EnumSet.of(NotificationType.SYSTEM, NotificationType.MODERATION_OUTCOME);

    /**
     * The "no usable contact" sentinel — used when the recipient has no resolvable contact (no profile, or a
     * missing/withheld value). Carrying a non-null empty contact (rather than a {@code null}) keeps the
     * send path branch-free: each channel asks {@code hasMsisdn()}/{@code hasEmail()} and skips gracefully
     * when absent (EI-3/6 — degrade, never crash, never NPE on a fan-out).
     */
    private static final RecipientContact EMPTY_CONTACT = new RecipientContact(null, null);

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final SmsGateway smsGateway;
    private final PushSender pushSender;
    private final EmailSender emailSender;
    private final RecipientContactApi recipientContactApi;
    private final ClockPort clock;

    /**
     * @param notificationRepository dispatch-row persistence (idempotency-gated).
     * @param preferenceRepository   per-recipient preference lookup.
     * @param smsGateway             SMS port (existing; reused from auth).
     * @param pushSender             push port.
     * @param emailSender            email port.
     * @param recipientContactApi    identity's published contact-resolution port (ADR-0013 §1) — resolves a
     *                               recipient's raw MSISDN/email so the SMS/email rows address a real
     *                               destination. The resolved contact is PII: it is handed straight to the
     *                               masking gateway/sender and never logged, stored, or put in an event (S-4).
     * @param clock                  injectable "now" for quiet-hour + send timestamps (testability).
     */
    public NotificationDispatchService(NotificationRepository notificationRepository,
                                       NotificationPreferenceRepository preferenceRepository,
                                       SmsGateway smsGateway,
                                       PushSender pushSender,
                                       EmailSender emailSender,
                                       RecipientContactApi recipientContactApi,
                                       ClockPort clock) {
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
        this.smsGateway = smsGateway;
        this.pushSender = pushSender;
        this.emailSender = emailSender;
        this.recipientContactApi = recipientContactApi;
        this.clock = clock;
    }

    /**
     * Dispatches one event to one recipient over the requested channels, after applying the recipient's
     * preferences and the inclusion invariants.
     *
     * @param recipientProfileId the recipient profile's public id.
     * @param type               the event type.
     * @param requestedChannels  the channels the source (e.g. announcement) selected.
     * @param payloadRef         a reference to the source content (deep-link), or {@code null}.
     * @param sourceId           the source entity's public id (announcement id, …) for idempotency keys.
     * @param title              the (already-localised) short title for push.
     * @param body               the (already-localised) short body for push/SMS/email.
     * @return the persisted notifications that were created/found for this recipient (one per channel
     *         actually selected after preferences + fallback).
     */
    @Transactional
    public List<Notification> dispatch(UUID recipientProfileId, NotificationType type,
                                       Set<Channel> requestedChannels, String payloadRef, UUID sourceId,
                                       String title, String body) {
        Map<Channel, NotificationPreference> prefs = loadPrefs(recipientProfileId, type);
        LocalTime localNow = LocalTime.ofInstant(clock.now(), LOCAL_ZONE);

        // Resolve the channels to actually use (preference + always-on + quiet-hours).
        Set<Channel> selected = selectChannels(type, requestedChannels, prefs, localNow);

        // SMS fallback for push-with-no-token is decided at send time (below), but only if SMS is
        // permissible for this recipient/type — precompute that allowance once.
        boolean smsAllowed = isChannelAllowed(type, Channel.SMS,
                requestedChannels.contains(Channel.SMS), prefs.get(Channel.SMS), localNow, true);

        // Resolve the recipient's raw contact (MSISDN/email) ONCE, only if a contact-bearing channel could
        // fire (an SMS row, an email row, or a PUSH that may fall back to SMS). FEED-only dispatches do no
        // identity round-trip. The contact is PII (S-4): it is threaded transiently to the masking
        // gateway/sender below and is never logged, stored, or placed in an event/feed (ADR-0013 PII rule).
        RecipientContact contact = needsContact(selected, smsAllowed)
                ? recipientContactApi.contactFor(recipientProfileId).orElse(EMPTY_CONTACT)
                : EMPTY_CONTACT;

        return selected.stream()
                .map(channel -> queueAndSend(recipientProfileId, type, channel, payloadRef, sourceId,
                        title, body, smsAllowed, contact))
                .toList();
    }

    /**
     * @return {@code true} if any selected channel needs a resolved contact — an SMS or EMAIL row, or a PUSH
     *         that may degrade to SMS — so a FEED/PUSH-only dispatch with no SMS fallback never pays for a
     *         contact lookup (KISS on the fan-out hot path).
     */
    private boolean needsContact(Set<Channel> selected, boolean smsAllowed) {
        return selected.contains(Channel.SMS)
                || selected.contains(Channel.EMAIL)
                || (selected.contains(Channel.PUSH) && smsAllowed);
    }

    /** Loads the recipient's prefs for this type, keyed by channel for O(1) lookup. */
    private Map<Channel, NotificationPreference> loadPrefs(UUID recipientProfileId, NotificationType type) {
        return preferenceRepository.findByProfileId(recipientProfileId).stream()
                .filter(p -> p.getType() == type)
                .collect(Collectors.toMap(NotificationPreference::getChannel, Function.identity(),
                        (a, b) -> a));
    }

    /**
     * Computes the channels to deliver over: FEED is always included (durable, never suppressed); other
     * requested channels are included only if the recipient's preference (or the default) allows them and
     * quiet hours do not suppress an interruptive channel.
     */
    private Set<Channel> selectChannels(NotificationType type, Set<Channel> requested,
                                        Map<Channel, NotificationPreference> prefs, LocalTime localNow) {
        Set<Channel> selected = EnumSet.noneOf(Channel.class);
        // FEED: always retained when requested (or implicitly, for always-on types) — EI-5.
        if (requested.contains(Channel.FEED) || ALWAYS_ON.contains(type)) {
            selected.add(Channel.FEED);
        }
        for (Channel channel : List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL)) {
            boolean wasRequested = requested.contains(channel);
            boolean interruptive = channel == Channel.PUSH || channel == Channel.SMS;
            if (isChannelAllowed(type, channel, wasRequested, prefs.get(channel), localNow, interruptive)) {
                selected.add(channel);
            }
        }
        return selected;
    }

    /**
     * Decides whether a single non-FEED channel is permitted for this recipient/type now.
     *
     * <p>Rules (PRD §13): a channel must have been requested by the source; an explicit opt-out blocks it
     * unless the type is always-on; with no preference row the default is opt-in for PUSH/EMAIL and
     * <b>opt-out for SMS</b> (SMS has a real cost — a citizen is never silently charged); quiet hours
     * suppress interruptive channels (PUSH/SMS) but never the always-on types.</p>
     */
    private boolean isChannelAllowed(NotificationType type, Channel channel, boolean wasRequested,
                                     NotificationPreference pref, LocalTime localNow, boolean interruptive) {
        if (!wasRequested) {
            return false;
        }
        boolean alwaysOn = ALWAYS_ON.contains(type);
        if (pref != null) {
            if (!pref.isEnabled() && !alwaysOn) {
                return false;
            }
            if (interruptive && !alwaysOn && pref.isQuietAt(localNow)) {
                return false;
            }
        } else {
            // No preference row → default. SMS defaults OFF (cost); others default ON.
            if (channel == Channel.SMS && !alwaysOn) {
                return false;
            }
        }
        return true;
    }

    /**
     * Idempotently queues a notification for one channel, then attempts delivery, recording the outcome.
     * On PUSH with no device token, falls back to SMS if SMS is permissible for this recipient.
     *
     * @param contact the recipient's pre-resolved raw MSISDN/email ({@link #EMPTY_CONTACT} when none) — PII
     *                handed only to the masking gateway/sender, never logged here (S-4).
     */
    private Notification queueAndSend(UUID recipientProfileId, NotificationType type, Channel channel,
                                      String payloadRef, UUID sourceId, String title, String body,
                                      boolean smsAllowed, RecipientContact contact) {
        String key = idempotencyKey(type, channel, recipientProfileId, sourceId);
        Optional<Notification> existing = notificationRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            // Already dispatched (replay/retry) — do not re-send (DI4).
            return existing.get();
        }
        Notification n = notificationRepository.save(
                Notification.queue(recipientProfileId, type, channel, payloadRef, key));
        deliver(n, recipientProfileId, type, sourceId, title, body, smsAllowed, contact);
        return n;
    }

    /**
     * Attempts delivery over the row's channel; FEED is delivered by persistence alone (retained item).
     * SMS/email are addressed with the recipient's pre-resolved raw contact ({@code contact}); a missing
     * contact for the channel is a graceful skip (row marked {@code FAILED} with a non-PII reason), never a
     * crash (EI-3/6).
     */
    private void deliver(Notification n, UUID recipientProfileId, NotificationType type, UUID sourceId,
                         String title, String body, boolean smsAllowed, RecipientContact contact) {
        switch (n.getChannel()) {
            case FEED ->
                // The feed item IS the persisted row; mark delivered immediately (EI-5).
                    n.markDelivered(clock.now());
            case PUSH -> {
                // PUSH addresses the recipient by their (non-PII) profile id ref — the push provider maps it
                // to the device token registry; it never carries a phone/email.
                PushSender.PushResult r = pushSender.send(new PushSender.PushMessage(
                        recipientProfileId.toString(), title, body,
                        sourceId == null ? null : sourceId.toString(), n.getIdempotencyKey()));
                if (r.accepted()) {
                    n.markSent(clock.now());
                } else if (r.noToken() && smsAllowed) {
                    // Degrade push → SMS (US-5.1, EI-5): record this row failed-over, queue an SMS row.
                    n.markFailed("NO_PUSH_TOKEN_FELL_BACK_TO_SMS");
                    fallbackToSms(recipientProfileId, type, sourceId, body, contact);
                } else {
                    n.markFailed(r.reason() == null ? "PUSH_FAILED" : r.reason());
                }
            }
            case SMS ->
                    sendSms(n, contact, body);
            case EMAIL -> {
                if (!contact.hasEmail()) {
                    // No deliverable (verified) email for this recipient — skip gracefully (EI-6), never send
                    // to a missing/unverified address. The row records the skip with a non-PII reason.
                    n.markFailed("NO_EMAIL_FOR_RECIPIENT");
                    return;
                }
                // The sender masks the address before logging; the raw value is passed only here, not logged.
                EmailSender.EmailResult r = emailSender.send(new EmailSender.EmailMessage(
                        contact.email(), title, body, type.name(), n.getIdempotencyKey()));
                if (r.accepted()) {
                    n.markSent(clock.now());
                } else {
                    n.markFailed(r.reason() == null ? "EMAIL_FAILED" : r.reason());
                }
            }
        }
    }

    /**
     * Queues + sends an SMS fallback row (separate idempotency key so it is its own auditable delivery),
     * addressed with the recipient's pre-resolved MSISDN ({@code contact}).
     */
    private void fallbackToSms(UUID recipientProfileId, NotificationType type, UUID sourceId, String body,
                               RecipientContact contact) {
        String key = idempotencyKey(type, Channel.SMS, recipientProfileId, sourceId) + ":fallback";
        if (notificationRepository.existsByIdempotencyKey(key)) {
            return;
        }
        Notification sms = notificationRepository.save(
                Notification.queue(recipientProfileId, type, Channel.SMS, null, key));
        sendSms(sms, contact, body);
    }

    /**
     * Sends one SMS to the recipient's pre-resolved MSISDN and records the outcome; never throws on a routine
     * failure (EI-3). A recipient with no usable MSISDN is a graceful skip (row {@code FAILED} with a non-PII
     * reason), not a send to a bad number and not a crash.
     *
     * @param contact the recipient's resolved contact; only {@link RecipientContact#msisdn()} is read here.
     *                The raw number is passed straight to the masking {@code SmsGateway} — never logged (S-4).
     */
    private void sendSms(Notification n, RecipientContact contact, String body) {
        if (!contact.hasMsisdn()) {
            // No phone resolved (no profile, or an anonymised/tombstoned recipient) — skip gracefully (EI-3).
            n.markFailed("NO_MSISDN_FOR_RECIPIENT");
            return;
        }
        SmsGateway.SmsSendResult r = smsGateway.send(new SmsGateway.SmsMessage(
                contact.msisdn(), body, "NOTIFICATION", n.getIdempotencyKey()));
        if (r.accepted()) {
            n.markSent(clock.now());
        } else {
            n.markFailed(r.reason() == null ? "SMS_FAILED" : r.reason());
        }
    }

    /**
     * Builds the stable idempotency key for a (type, channel, recipient, source) dispatch. Non-PII and
     * deterministic so a replay collides with the original (DI4).
     */
    private String idempotencyKey(NotificationType type, Channel channel, UUID recipient, UUID source) {
        return type.name() + ":" + channel.name() + ":" + recipient + ":" + (source == null ? "-" : source);
    }
}
