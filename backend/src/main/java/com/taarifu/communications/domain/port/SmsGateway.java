package com.taarifu.communications.domain.port;

/**
 * Outbound port for sending SMS — above all the OTP code (AUTH-DESIGN §12, ADR-0011 §9, EI-3).
 *
 * <p>Responsibility: abstracts "deliver this short message to this phone" so the OTP/notification flows
 * never depend on a concrete aggregator. The body is built by the caller from i18n templates (Swahili
 * default, English secondary) and must be <b>UCS-2 capable</b> so Swahili diacritics survive; it must
 * <b>never</b> contain PII beyond the OTP code itself. The real least-cost/DLR aggregator adapter (with
 * email fallback on outage — the citizen path never hard-fails) lands in the {@code communications}
 * integration increment; a logging dev stub stands in now.</p>
 *
 * <p>WHY a port (not aggregator calls inline): it isolates the aggregator's session/DLR quirks and the
 * degradation mode behind one seam (ADR-0004), and lets dev/test run with <b>zero external calls</b> via
 * the stub adapter.</p>
 */
public interface SmsGateway {

    /**
     * Sends one SMS.
     *
     * @param message the recipient (E.164), body, and delivery context.
     * @return the send result (queued/accepted/failed + an optional provider id), never throwing for a
     *         routine delivery failure — the caller decides on fallback (degrade, don't crash; EI-3).
     */
    SmsSendResult send(SmsMessage message);

    /**
     * An SMS to send.
     *
     * @param recipientE164 the destination phone in E.164. Handle as PII — never log it raw.
     * @param body          the message text (UCS-2 safe). For OTP this is the only place the code appears;
     *                      the code is never logged (S-4).
     * @param purpose       a non-PII purpose tag for metrics/idempotency (e.g. {@code "SIGNUP_OTP"}).
     * @param idempotencyKey a stable key (e.g. the challenge public id) so a relay retry never double-sends.
     */
    record SmsMessage(String recipientE164, String body, String purpose, String idempotencyKey) {
    }

    /**
     * The outcome of a send.
     *
     * @param accepted          whether the gateway accepted/queued the message.
     * @param providerMessageId the provider's message id, or {@code null}.
     * @param reason            a non-PII reason on failure, or {@code null} on success.
     */
    record SmsSendResult(boolean accepted, String providerMessageId, String reason) {

        /** @return an accepted result with the given provider id. */
        public static SmsSendResult accepted(String providerMessageId) {
            return new SmsSendResult(true, providerMessageId, null);
        }

        /** @return a failed result with a non-PII reason. */
        public static SmsSendResult failed(String reason) {
            return new SmsSendResult(false, null, reason);
        }
    }
}
