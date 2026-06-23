package com.taarifu.communications.domain.port;

/**
 * Outbound port for transactional email — staff/role notifications, digests, OTP fallback
 * (PRD §13, EI-6, ARCHITECTURE §7).
 *
 * <p>Responsibility: abstracts "deliver this email to this address" so the dispatcher never depends on a
 * concrete ESP. The subject/body are built from i18n templates in the recipient's language (Swahili
 * default — ADR-0010). The real ESP adapter (SPF/DKIM/DMARC aligned, with a bounce webhook) lands in the
 * integration increment; a logging dev stub stands in now.</p>
 *
 * <p>WHY a port (not ESP calls inline): it isolates the ESP and its bounce/DLR quirks behind one seam
 * (ARCHITECTURE §7) and lets dev/test run with <b>zero external calls</b>. Degradation (EI-6): on outage
 * the send is queued with retry and the citizen path never blocks (signup completes without email).</p>
 */
public interface EmailSender {

    /**
     * Sends one email.
     *
     * @param message the recipient address, localized subject/body, and an idempotency key.
     * @return the send result; never throws for a routine delivery failure — the caller decides on
     *         retry/fallback (degrade, don't crash; EI-6).
     */
    EmailResult send(EmailMessage message);

    /**
     * An email to send.
     *
     * @param recipientEmail the destination address. Handle as PII — never log it raw.
     * @param subject        the localized subject.
     * @param body           the localized body (no sensitive content beyond what the type warrants).
     * @param purpose        a non-PII purpose tag for metrics/idempotency (e.g. {@code "DIGEST"}).
     * @param idempotencyKey a stable key so a relay retry never double-sends (DI4).
     */
    record EmailMessage(String recipientEmail, String subject, String body, String purpose,
                        String idempotencyKey) {
    }

    /**
     * The outcome of an email send.
     *
     * @param accepted          whether the ESP accepted/queued the message.
     * @param providerMessageId the ESP's message id, or {@code null}.
     * @param reason            a non-PII reason on failure, or {@code null} on success.
     */
    record EmailResult(boolean accepted, String providerMessageId, String reason) {

        /** @return an accepted result with the given provider id. */
        public static EmailResult accepted(String providerMessageId) {
            return new EmailResult(true, providerMessageId, null);
        }

        /** @return a failed result with a non-PII reason. */
        public static EmailResult failed(String reason) {
            return new EmailResult(false, null, reason);
        }
    }
}
