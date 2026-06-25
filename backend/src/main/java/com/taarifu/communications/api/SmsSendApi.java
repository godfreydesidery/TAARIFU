package com.taarifu.communications.api;

/**
 * The communications module's <b>public, in-process command port</b> for sending one outbound SMS
 * (ADR-0013 §1; ADR-0019; A3; PRD §13/§14, EI-3). A sibling channel that must send a short SMS but does not
 * own SMS delivery — above all the feature-phone {@code ussd} module's <b>ticket-confirmation SMS</b>
 * (UC-D02: "ticket code returned by SMS") — depends on, and calls, this interface synchronously
 * ({@code ussd → communications}), <b>without</b> importing communications' internal
 * {@code domain.port.SmsGateway} (ARCHITECTURE §3.2). It is the published façade over the internal
 * {@code SmsGateway} (which the real least-cost/DLR aggregator adapter or the prod-safe logging stub backs).
 *
 * <p>Responsibility: expose the single operation "deliver this short message to this phone" as the sanctioned
 * cross-module contract, so the aggregator's session/DLR quirks, the degradation mode, and the
 * <b>recipient-masking discipline</b> all stay owned inside communications. The caller builds the body from
 * its own GSM-7/UCS-2-safe, Swahili-first templates and treats the result as opaque.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA; ADR-0013 PII rule, S-4):</b> the command carries a <b>raw</b> E.164
 * recipient — unavoidable, because the gateway must address a real handset — and the body. The implementation
 * hands these straight to the masking {@code SmsGateway} and <b>never logs the raw number or the body, never
 * persists them, and never places them in an event, feed item, or audit row</b>. The {@code purpose} and
 * {@code idempotencyKey} are non-PII tags only.</p>
 *
 * <p><b>🔒 Civic-integrity fence (D18, §23.5):</b> this is a pure delivery port — there is deliberately
 * <b>no</b> token/balance input or output and nothing here reads a wallet. A feature-phone citizen's
 * confirmation SMS is never priced or gated.</p>
 *
 * <p>Fail-soft (EI-3): the method returns an accepted/failed {@link SmsSendResult} and <b>never throws</b> for
 * a routine delivery failure — a confirmation SMS must never break the citizen flow that triggered it. The
 * caller decides whether to retry/fallback; the END line of the USSD dialogue already carries the ticket code.</p>
 */
public interface SmsSendApi {

    /**
     * Sends one SMS, never throwing for a routine delivery failure (EI-3).
     *
     * @param command the recipient (E.164 — PII, never logged raw), body, non-PII purpose tag, and a stable
     *                idempotency key.
     * @return the send outcome (accepted/queued or failed with a non-PII reason); never {@code null}.
     */
    SmsSendResult send(SmsSendCommand command);

    /**
     * An SMS to send across the module boundary.
     *
     * @param recipientE164  the destination phone in E.164. <b>PII</b> — the caller must not log it raw; the
     *                       impl hands it straight to the masking gateway (S-4).
     * @param body           the GSM-7/UCS-2-safe message text (e.g. the ticket code). Carries no PII beyond the
     *                       recipient.
     * @param purpose        a non-PII purpose tag for metrics/idempotency (e.g. {@code "USSD_TICKET"}).
     * @param idempotencyKey a stable key (e.g. the USSD session id + purpose) so a relay/retry never double-sends.
     */
    record SmsSendCommand(String recipientE164, String body, String purpose, String idempotencyKey) {
    }

    /**
     * The outcome of a send.
     *
     * @param accepted          whether the gateway accepted/queued the message.
     * @param providerMessageId the provider's message id, or {@code null}.
     * @param reason            a non-PII reason on failure, or {@code null} on success.
     */
    record SmsSendResult(boolean accepted, String providerMessageId, String reason) {

        /**
         * @param providerMessageId the provider's message id (may be {@code null}).
         * @return an accepted result.
         */
        public static SmsSendResult accepted(String providerMessageId) {
            return new SmsSendResult(true, providerMessageId, null);
        }

        /**
         * @param reason a non-PII failure reason.
         * @return a failed result.
         */
        public static SmsSendResult failed(String reason) {
            return new SmsSendResult(false, null, reason);
        }
    }
}
