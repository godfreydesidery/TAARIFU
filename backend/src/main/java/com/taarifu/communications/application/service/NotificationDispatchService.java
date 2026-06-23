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
 * <p>WHY this is synchronous-but-fail-soft here (not yet a worker): the transactional-outbox + worker
 * substrate is a later increment (ARCHITECTURE §8). This service is written so it can be moved behind the
 * bus unchanged — it never throws on a routine channel failure (it records {@code FAILED}/falls back), so
 * the caller's transaction (e.g. publishing the announcement) never rolls back on a provider outage
 * (DI3). TODO(wiring): invoke this from the {@code AnnouncementPublished} outbox consumer once it exists.</p>
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /** Time zone for evaluating a recipient's quiet hours when no per-pref zone is modelled (EAT). */
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    /** Types that cannot be silenced by an opt-out (PRD §13 "always"). */
    private static final Set<NotificationType> ALWAYS_ON =
            EnumSet.of(NotificationType.SYSTEM, NotificationType.MODERATION_OUTCOME);

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final SmsGateway smsGateway;
    private final PushSender pushSender;
    private final EmailSender emailSender;
    private final ClockPort clock;

    /**
     * @param notificationRepository dispatch-row persistence (idempotency-gated).
     * @param preferenceRepository   per-recipient preference lookup.
     * @param smsGateway             SMS port (existing; reused from auth).
     * @param pushSender             push port.
     * @param emailSender            email port.
     * @param clock                  injectable "now" for quiet-hour + send timestamps (testability).
     */
    public NotificationDispatchService(NotificationRepository notificationRepository,
                                       NotificationPreferenceRepository preferenceRepository,
                                       SmsGateway smsGateway,
                                       PushSender pushSender,
                                       EmailSender emailSender,
                                       ClockPort clock) {
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
        this.smsGateway = smsGateway;
        this.pushSender = pushSender;
        this.emailSender = emailSender;
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

        return selected.stream()
                .map(channel -> queueAndSend(recipientProfileId, type, channel, payloadRef, sourceId,
                        title, body, smsAllowed))
                .toList();
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
     */
    private Notification queueAndSend(UUID recipientProfileId, NotificationType type, Channel channel,
                                      String payloadRef, UUID sourceId, String title, String body,
                                      boolean smsAllowed) {
        String key = idempotencyKey(type, channel, recipientProfileId, sourceId);
        Optional<Notification> existing = notificationRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            // Already dispatched (replay/retry) — do not re-send (DI4).
            return existing.get();
        }
        Notification n = notificationRepository.save(
                Notification.queue(recipientProfileId, type, channel, payloadRef, key));
        deliver(n, recipientProfileId, type, sourceId, title, body, smsAllowed);
        return n;
    }

    /** Attempts delivery over the row's channel; FEED is delivered by persistence alone (retained item). */
    private void deliver(Notification n, UUID recipientProfileId, NotificationType type, UUID sourceId,
                         String title, String body, boolean smsAllowed) {
        switch (n.getChannel()) {
            case FEED ->
                // The feed item IS the persisted row; mark delivered immediately (EI-5).
                    n.markDelivered(clock.now());
            case PUSH -> {
                PushSender.PushResult r = pushSender.send(new PushSender.PushMessage(
                        recipientProfileId.toString(), title, body,
                        sourceId == null ? null : sourceId.toString(), n.getIdempotencyKey()));
                if (r.accepted()) {
                    n.markSent(clock.now());
                } else if (r.noToken() && smsAllowed) {
                    // Degrade push → SMS (US-5.1, EI-5): record this row failed-over, queue an SMS row.
                    n.markFailed("NO_PUSH_TOKEN_FELL_BACK_TO_SMS");
                    fallbackToSms(recipientProfileId, type, sourceId, body);
                } else {
                    n.markFailed(r.reason() == null ? "PUSH_FAILED" : r.reason());
                }
            }
            case SMS ->
                // SMS body carries no PII beyond the message; recipient resolution (MSISDN) is the
                // adapter's job — here we pass the profile id ref, the adapter maps to the phone.
                // TODO(wiring): resolve the recipient MSISDN via identity's public API in the adapter.
                    sendSms(n, recipientProfileId, body);
            case EMAIL -> {
                // TODO(wiring): resolve the recipient email via identity's public API in the adapter.
                EmailSender.EmailResult r = emailSender.send(new EmailSender.EmailMessage(
                        recipientProfileId.toString(), title, body, type.name(), n.getIdempotencyKey()));
                if (r.accepted()) {
                    n.markSent(clock.now());
                } else {
                    n.markFailed(r.reason() == null ? "EMAIL_FAILED" : r.reason());
                }
            }
        }
    }

    /** Queues + sends an SMS fallback row (separate idempotency key so it is its own auditable delivery). */
    private void fallbackToSms(UUID recipientProfileId, NotificationType type, UUID sourceId, String body) {
        String key = idempotencyKey(type, Channel.SMS, recipientProfileId, sourceId) + ":fallback";
        if (notificationRepository.existsByIdempotencyKey(key)) {
            return;
        }
        Notification sms = notificationRepository.save(
                Notification.queue(recipientProfileId, type, Channel.SMS, null, key));
        sendSms(sms, recipientProfileId, body);
    }

    /** Sends one SMS and records the outcome; never throws on a routine failure (EI-3). */
    private void sendSms(Notification n, UUID recipientProfileId, String body) {
        SmsGateway.SmsSendResult r = smsGateway.send(new SmsGateway.SmsMessage(
                // The adapter resolves the destination MSISDN from the profile ref; we never log a phone.
                recipientProfileId.toString(), body, "NOTIFICATION", n.getIdempotencyKey()));
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
