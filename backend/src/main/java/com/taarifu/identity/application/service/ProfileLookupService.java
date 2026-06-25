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
 * {@code feature → identity} "resolve a profile's public id / display name / tier" read seam (ADR-0013 §1, §4;
 * PRD §12.2 author labelling), backing the engagement {@code resolve creatorPublicId (account) → identity
 * Profile public id} wiring on petition/poll/question authoring.
 *
 * <p>Responsibility: two pure reads over the {@code Profile} ↔ {@code app_user} (1:1) aggregate identity owns —
 * map an <b>account</b> public id to its <b>profile</b> public id, and map a <b>profile</b> public id to its
 * public summary (id + display name + live trust-tier name). It owns only this read mapping; authoring,
 * validation, and rendering are the callers' concerns.</p>
 *
 * <p><b>🔒 PII discipline (PRD §18, PDPA):</b> this service reads and returns <b>only</b> public ids, the
 * public display name (via {@link Profile#displayName()}), and the public trust-tier name. It never reads or
 * returns the national/voter {@code idNo}/blind index, the phone/email (contact PII has its own consent-fenced
 * {@code RecipientContactApi} path), or any demographic, and it <b>logs nothing</b> (S-4). A missing id is a
 * silent empty (deny-by-default), never an exception — a read path must never crash because one author cannot
 * be resolved.</p>
 *
 * <p>WHY the tier is resolved through {@link TierService} (not read off a cached flag): the live tier resolver
 * is the single tier authority (MF-2, AUTH-DESIGN §7.1) — reusing it keeps the T0–T3 predicate in one place
 * (DRY) and means the author badge reflects a downgrade (e.g. revoked ID verification, §25.5) immediately,
 * never a stale cached hint. The tier crosses the boundary as its {@code name()} ("T0".."T3"), so callers never
 * import identity's internal {@code TrustTier} enum (ADR-0013 §3 — same convention as {@code UserAdminSummary}).</p>
 *
 * <p>WHY {@code readOnly} and no audit row: pure reads on display/list paths; they mutate nothing and emit no
 * event, so an audit row per resolved author would only bloat the log (L-1).</p>
 */
@Service
@Transactional(readOnly = true)
public class ProfileLookupService implements ProfileLookupApi {

    private final ProfileRepository profileRepository;
    private final TierService tierService;

    /**
     * @param profileRepository resolves a profile by its own public id, or by its owning account's public id
     *                          (the 1:1 link), the single source of the public id ↔ display-name pairing.
     * @param tierService       the single live trust-tier authority (MF-2); resolves the public T0–T3 badge for
     *                          a loaded profile (reused, never re-implemented — DRY).
     */
    public ProfileLookupService(ProfileRepository profileRepository, TierService tierService) {
        this.profileRepository = profileRepository;
        this.tierService = tierService;
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
        // Public id + public display name + live trust-tier name ONLY — never any ID/contact/demographic PII
        // (see ProfileSummary). The tier comes from the single live authority (TierService, MF-2), resolved off
        // the already-loaded profile (no re-query) and crossing the boundary as its name() so callers never
        // import identity's TrustTier enum (ADR-0013 §3).
        return profileRepository.findByPublicId(profilePublicId)
                .map(p -> new ProfileSummary(
                        p.getPublicId(),
                        p.displayName(),
                        tierService.resolveLiveTier(p).name()));
    }
}
