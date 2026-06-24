package com.taarifu.identity.api;

import com.taarifu.identity.api.dto.RecipientContact;

import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's <b>public, in-process query port</b> for resolving a single recipient's deliverable
 * contact points (MSISDN and/or email) for the notification dispatch adapters (PRD §13 SMS/email channels,
 * EI-3/6; ADR-0013 §1, §4 "communications → owning module's {@code *QueryApi} to resolve a referenced
 * subject"). The {@code communications} module calls this synchronously ({@code communications → identity})
 * to address an SMS/email it is about to send, <b>without</b> importing identity's {@code domain}/
 * {@code infrastructure} (ARCHITECTURE §3.2) — the same shape as {@link ElectoralScopeApi}/
 * {@link UserAdminQueryApi}.
 *
 * <p>Responsibility: answer the single question "what raw phone/email do I send to for this recipient?",
 * resolved from the recipient's identity {@code Profile} → its owning account's verified phone and email.
 * Identity owns this because identity owns the contact PII (it stores it, decides verification, and would
 * decrypt it were it encrypted); the caller treats the result as the only source of a deliverable address
 * and does not reach past it (it never reads the account/profile itself).</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA; ADR-0013 PII rule):</b> the returned {@link RecipientContact}
 * carries the <b>raw</b> contact value <i>only because the dispatch adapter must address a real message</i>
 * — it is the one identity port that does not mask. Its use is fenced by contract: it is for the SMS/email
 * <b>dispatch adapters ONLY</b>; the caller consumes it transiently to hand to the {@code SmsGateway}/
 * {@code EmailSender} (which mask before logging) and <b>never stores it, never logs it raw, and never
 * places it in an event, feed item, or audit row</b> (S-4). No {@code idNo} or other PII is exposed —
 * contact points only. A recipient with no resolvable contact (no profile for the id) yields
 * {@link Optional#empty()} so the dispatcher skips the channel gracefully rather than crashing
 * (deny-by-default; EI-3/6).</p>
 *
 * <p>WHY a dedicated narrow port (not a field on a broader profile DTO): least privilege — only the dispatch
 * path needs the raw contact, so it is isolated behind one small, audit-greppable interface (ISP, ADR-0013
 * "one port per concern, kept small") rather than widening a general profile view to leak contact PII to
 * every caller.</p>
 */
public interface RecipientContactApi {

    /**
     * Resolves the deliverable contact points for the recipient identified by their profile public id.
     *
     * <p>Resolution: the profile public id → its identity {@code Profile} → the owning account's phone
     * (always present — the unique account key, D11/D15) and email (included only when the profile's email
     * is <b>verified</b>; an unverified or absent email is withheld — never send to an unproven address).
     * A missing profile yields {@link Optional#empty()} (deny-by-default — the dispatcher skips SMS/email
     * for this recipient, never crashing the fan-out; EI-3/6).</p>
     *
     * @param recipientProfilePublicId the recipient's identity profile public id — the grain the
     *                                 notification dispatcher and subscription fan-out carry (a profile id,
     *                                 not the account id; see {@code AnnouncementPublishedHandler}).
     *                                 {@code null} resolves to empty.
     * @return the recipient's contact points (raw MSISDN and/or verified email — PII the caller must not
     *         store/log raw), or empty if no profile resolves for the id.
     */
    Optional<RecipientContact> contactFor(UUID recipientProfilePublicId);
}
