package com.taarifu.ussd.application.port;

/**
 * Consumer-owned outbound port for the SMS confirmations/status the USSD flows send (PRD §14, EI-3).
 *
 * <p>Responsibility: deliver the short outbound message a USSD interaction triggers — above all the
 * <b>ticket code by SMS</b> after a successful filing (UC-D02: "ticket code returned by SMS"), and status
 * replies. The body is built here from GSM-7-safe Swahili-first templates and carries <b>no PII beyond the
 * recipient</b> (S-4).</p>
 *
 * <p>WHY a consumer-owned port rather than importing communications' {@code SmsGateway}: the task calls for
 * sending via the existing communications SMS port, but that port lives in
 * {@code communications.domain.port} — an <b>internal</b> layer the isolation rule forbids this module from
 * importing (cross-module reach must go through the callee's {@code com.taarifu.<callee>.api} package,
 * ADR-0013 §1). So this module defines its own outbound seam, bound to a dev stub now, and its production
 * adapter delegates to communications' SMS capability once it is republished in {@code communications.api}
 * ({@code // TODO(wiring)}; see CENTRAL INTEGRATION NEEDS). This keeps the boundary clean and the system
 * bootable/testable with zero external calls (ARCHITECTURE §7).</p>
 */
public interface UssdSmsSender {

    /**
     * Sends one SMS, never throwing for a routine delivery failure — a USSD dialogue must end cleanly even
     * if the confirmation SMS is delayed (degrade, don't crash; EI-3). The aggregator queues/retries.
     *
     * @param recipientE164  the destination phone in E.164. PII — never log it raw (S-4).
     * @param body           the GSM-7-safe message text (e.g. the ticket code).
     * @param idempotencyKey a stable key (e.g. the session id + purpose) so a retry never double-sends.
     */
    void send(String recipientE164, String body, String idempotencyKey);
}
