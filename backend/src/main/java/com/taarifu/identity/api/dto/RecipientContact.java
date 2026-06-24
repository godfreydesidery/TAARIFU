package com.taarifu.identity.api.dto;

/**
 * A single recipient's deliverable contact points — the destination MSISDN and/or email — resolved by
 * {@link com.taarifu.identity.api.RecipientContactApi} for the notification dispatch adapters
 * (PRD §13 SMS/email channels, EI-3/6; ADR-0013 §1).
 *
 * <p>Responsibility: carry the <b>raw</b>, deliverable contact value across the identity boundary so the
 * SMS gateway / email sender adapters can address a real message — and nothing more. It is deliberately the
 * smallest slice that lets a message be sent: a phone (E.164) for SMS, an address for email; either may be
 * {@code null} when the recipient has no usable value on that channel (no phone is impossible for an
 * account, but a recipient may have no email, or an unverified one this view withholds).</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA — data minimisation; ADR-0013 PII rule):</b> unlike every other
 * identity {@code api} projection (e.g. {@link UserAdminSummary}, which exposes only a <i>masked</i> phone),
 * this record carries the <b>raw</b> contact <i>because the adapter must dial/address it</i> — there is no
 * way to send an SMS to a masked number. That makes it the most sensitive DTO identity publishes, so its
 * use is fenced by contract: it is for the dispatch adapters ONLY, it is consumed transiently and
 * <b>never stored, never logged raw, never copied into an event/feed/audit payload</b>. The
 * {@code SmsGateway}/{@code EmailSender} ports already mask the value before logging; the dispatcher passes
 * this straight through to them and retains nothing. No {@code idNo} (national/voter ID) or any other PII
 * appears here — contact points only.</p>
 *
 * <p>WHY both channels in one record (not two calls): a single resolution per recipient is enough for the
 * dispatcher to address both the SMS and the email rows it may emit for one event, avoiding a second
 * boundary crossing (and a second decrypt, were these encrypted) on the fan-out hot path.</p>
 *
 * @param msisdn the recipient's phone in E.164 form for SMS, or {@code null} if none is usable. Raw PII —
 *               the consumer must never log it unmasked (S-4) and must not persist it.
 * @param email  the recipient's deliverable email for the email channel, or {@code null} if the account has
 *               no email or it is not verified (an unverified address is withheld — never send to an
 *               address the citizen has not proven they own). Raw PII — same handling discipline as
 *               {@code msisdn}.
 */
public record RecipientContact(String msisdn, String email) {

    /**
     * @return {@code true} if this recipient has a usable SMS destination (a non-blank E.164 phone). The
     *         dispatcher skips the SMS send gracefully when this is {@code false} rather than calling the
     *         gateway with no number (EI-3 — degrade, never crash).
     */
    public boolean hasMsisdn() {
        return msisdn != null && !msisdn.isBlank();
    }

    /**
     * @return {@code true} if this recipient has a usable (verified) email destination. The dispatcher skips
     *         the email send gracefully when this is {@code false} (EI-6 — degrade, never crash).
     */
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}
