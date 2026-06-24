package com.taarifu.identity.application.service;

import com.taarifu.identity.api.RecipientContactApi;
import com.taarifu.identity.api.dto.RecipientContact;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity's implementation of the published {@link RecipientContactApi} — the synchronous
 * {@code communications → identity} "resolve this recipient's deliverable phone/email" seam (ADR-0013 §1, §4;
 * PRD §13 SMS/email channels, EI-3/6).
 *
 * <p>Responsibility: map a recipient's <b>profile public id</b> to the raw contact points the notification
 * dispatch adapters need to address a real SMS/email — the account's phone (always present) and its email
 * (only when the profile's email is <b>verified</b>). It owns only this read mapping; channel selection,
 * preferences, idempotency, and masking are the dispatcher's/adapters' concerns, not this service's.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA; ADR-0013 PII rule):</b> this is the one identity service that
 * returns a <b>raw</b> contact value, because the adapter cannot send to a masked number. It therefore does
 * the minimum and nothing more: it reads the profile's account contact, returns it transiently, and
 * <b>logs nothing</b> — no phone, no email, not even at debug (S-4). The caller is contracted to hand the
 * value straight to the {@code SmsGateway}/{@code EmailSender} (which mask before logging) and to retain
 * nothing. No {@code idNo} or other PII is touched. A missing profile is a silent empty (deny-by-default),
 * never an exception — a notification fan-out must never crash because one recipient cannot be resolved
 * (EI-3/6).</p>
 *
 * <p>WHY {@code readOnly} and why no audit row: this is a pure read on the dispatch hot path; it mutates
 * nothing and emits no domain event. Recording an audit row per resolved contact would both bloat the audit
 * log on every fan-out and risk capturing the recipient set — neither is wanted (L-1). The act of <i>sending</i>
 * is recorded by the {@code Notification} row the dispatcher persists, keyed by id, never by contact.</p>
 */
@Service
@Transactional(readOnly = true)
public class RecipientContactService implements RecipientContactApi {

    private final ProfileRepository profileRepository;

    /**
     * @param profileRepository resolves a recipient's profile (by public id) → its owning account, the
     *                          single source of the deliverable phone/email.
     */
    public RecipientContactService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RecipientContact> contactFor(UUID recipientProfilePublicId) {
        if (recipientProfilePublicId == null) {
            // Deny-by-default: no id resolves to no contact (the dispatcher skips SMS/email gracefully).
            return Optional.empty();
        }
        // Profile → owning account. The @SQLRestriction on Profile/User excludes soft-deleted rows, so a
        // tombstoned (anonymised — PDPA erasure) recipient correctly resolves to empty and is never contacted.
        return profileRepository.findByPublicId(recipientProfilePublicId)
                .map(this::toContact);
    }

    /**
     * Maps a profile to its deliverable contact points: the account's phone (always set — the unique account
     * key, D11/D15) and its email <b>only when the profile's email is verified</b> (an unverified or absent
     * email is withheld so we never send to an address the citizen has not proven they own).
     *
     * @param profile the recipient's profile.
     * @return the recipient's raw contact points (PII — the caller must not store/log them raw).
     */
    private RecipientContact toContact(Profile profile) {
        User user = profile.getUser();
        String msisdn = user.getPhone();
        String email = profile.isEmailVerified() ? user.getEmail() : null;
        return new RecipientContact(msisdn, email);
    }
}
