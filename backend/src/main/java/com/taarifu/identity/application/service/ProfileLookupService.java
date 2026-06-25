package com.taarifu.identity.application.service;

import com.taarifu.identity.api.ProfileLookupApi;
import com.taarifu.identity.api.dto.ProfileSummary;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Identity's implementation of the published {@link ProfileLookupApi} — the synchronous
 * {@code feature → identity} "resolve a profile's public id / display name" read seam (ADR-0013 §1, §4;
 * PRD §12.2 author labelling), backing the engagement {@code // TODO(wiring): resolve creatorPublicId
 * (account) -> identity Profile public id} markers on petition/poll/question authoring.
 *
 * <p>Responsibility: two pure reads over the {@code Profile} ↔ {@code app_user} (1:1) aggregate identity owns —
 * map an <b>account</b> public id to its <b>profile</b> public id, and map a <b>profile</b> public id to its
 * public summary (id + display name). It owns only this read mapping; authoring, validation, and rendering are
 * the callers' concerns.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA):</b> this service reads and returns <b>only</b> public ids and the
 * public display name (via {@link Profile#displayName()}). It never reads or returns the national/voter
 * {@code idNo}/blind index, the phone/email (contact PII has its own consent-fenced {@code RecipientContactApi}
 * path), or any demographic, and it <b>logs nothing</b> (S-4). A missing id is a silent empty (deny-by-default),
 * never an exception — a read path must never crash because one author cannot be resolved.</p>
 *
 * <p>WHY {@code readOnly} and no audit row: pure reads on display/list paths; they mutate nothing and emit no
 * event, so an audit row per resolved author would only bloat the log (L-1).</p>
 */
@Service
@Transactional(readOnly = true)
public class ProfileLookupService implements ProfileLookupApi {

    private final ProfileRepository profileRepository;

    /**
     * @param profileRepository resolves a profile by its own public id, or by its owning account's public id
     *                          (the 1:1 link), the single source of the public id ↔ display-name pairing.
     */
    public ProfileLookupService(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> profileIdForAccount(UUID accountPublicId) {
        if (accountPublicId == null) {
            // Deny-by-default: no account id resolves to no profile (the caller treats it as "no profile").
            return Optional.empty();
        }
        // Account public id → 1:1 profile → its public id. The @SQLRestriction on profile/user excludes a
        // soft-deleted (anonymised — PDPA erasure) row, so an erased author correctly resolves to empty.
        return profileRepository.findByUser_PublicId(accountPublicId)
                .map(Profile::getPublicId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ProfileSummary> profileSummary(UUID profilePublicId) {
        if (profilePublicId == null) {
            return Optional.empty();
        }
        // Public id + public display name ONLY — never any ID/contact/demographic PII (see ProfileSummary).
        return profileRepository.findByPublicId(profilePublicId)
                .map(p -> new ProfileSummary(p.getPublicId(), p.displayName()));
    }
}
