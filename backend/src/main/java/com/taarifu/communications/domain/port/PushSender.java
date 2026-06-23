package com.taarifu.communications.domain.port;

/**
 * Outbound port for mobile push delivery — FCM HTTP v1 / APNs (PRD §13, EI-5, ARCHITECTURE §7).
 *
 * <p>Responsibility: abstracts "deliver this push to this device token" so the notification dispatcher
 * never depends on a concrete push vendor. The body is built from i18n templates in the recipient's
 * language (Swahili default — ADR-0010) and must stay <b>minimal</b>: a title + short body + a deep-link
 * ref, <b>never</b> sensitive content (private report bodies, PII) — push payloads transit third-party
 * infrastructure (PRD §18). The real FCM/APNs adapter (with a per-user multi-device token registry and
 * invalid-token pruning) lands in the integration increment; a logging dev stub stands in now.</p>
 *
 * <p>WHY a port (not vendor calls inline): it isolates FCM/APNs quirks and the degradation mode behind
 * one seam (ARCHITECTURE §7). Degradation (EI-5): when there is <b>no valid push token</b>, the
 * dispatcher falls back to SMS, and the FEED item is retained regardless — nothing is lost. This port
 * therefore signals "no token" distinctly so the caller can fall back rather than treat it as a hard
 * failure.</p>
 */
public interface PushSender {

    /**
     * Sends one push notification.
     *
     * @param message the recipient device-token reference, localized title/body, deep-link, and an
     *                idempotency key.
     * @return the send result; never throws for a routine delivery failure — the caller decides on
     *         fallback (SMS) or retry (degrade, don't crash; EI-5).
     */
    PushResult send(PushMessage message);

    /**
     * A push to send.
     *
     * @param recipientProfileId the recipient profile's public id (the adapter resolves device tokens
     *                           from its registry); a UUID string, never PII.
     * @param title              the localized title (short).
     * @param body               the localized body (short; no sensitive content — PRD §18).
     * @param deepLinkRef        an opaque deep-link/target ref (e.g. an announcement public id), or
     *                           {@code null}.
     * @param idempotencyKey     a stable key so a relay retry never double-sends (DI4).
     */
    record PushMessage(String recipientProfileId, String title, String body, String deepLinkRef,
                       String idempotencyKey) {
    }

    /**
     * The outcome of a push send.
     *
     * @param accepted whether the push provider accepted the message for at least one valid token.
     * @param noToken  {@code true} when the recipient has <b>no valid device token</b> — the dispatcher's
     *                 signal to fall back to SMS (US-5.1, EI-5), distinct from a provider error.
     * @param reason   a non-PII reason on non-acceptance, or {@code null} on success.
     */
    record PushResult(boolean accepted, boolean noToken, String reason) {

        /** @return an accepted result. */
        public static PushResult ok() {
            return new PushResult(true, false, null);
        }

        /** @return a "no valid device token" result — caller should fall back to SMS (EI-5). */
        public static PushResult noDeviceToken() {
            return new PushResult(false, true, "NO_PUSH_TOKEN");
        }

        /** @return a failed result with a non-PII reason (provider error). */
        public static PushResult failed(String reason) {
            return new PushResult(false, false, reason);
        }
    }
}
