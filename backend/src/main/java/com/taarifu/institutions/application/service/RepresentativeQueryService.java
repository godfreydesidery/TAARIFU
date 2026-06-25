package com.taarifu.institutions.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.identity.api.ProfileLookupApi;
import com.taarifu.institutions.api.RepresentativeQueryApi;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Live implementation of {@link RepresentativeQueryApi} — the institutions-owned resolution of a
 * representative's existence and constituency for cross-module binding-action scoping (ADR-0013 §1; D13).
 *
 * <p>Responsibility: validate the representative exists ({@link #exists} returns a boolean for the
 * curated-authoring guard; {@link #constituencyOf}/{@link #wardOf} throw {@code NOT_FOUND}), return the
 * public id of the constituency they hold (constituency-mandate MP) or the ward they hold
 * (councillor/ward-exec) — the two-tier electoral mapping the binding-action fence gates on (D13/F1) — and
 * answer the {@link #ownsRepresentative} account ↔ representative ownership guard the right-of-reply
 * conflict-of-interest fence consumes (US-6.2, D16). It is {@code @Transactional(readOnly = true)} and
 * exposes only DTO-grade {@code UUID}s/booleans — never the {@link Representative}/{@link Constituency}/
 * {@link Location} entities — to the caller (boundary discipline, CLAUDE.md §8).</p>
 *
 * <p>WHY this depends on identity's published {@link ProfileLookupApi}: the account ↔ representative link is
 * institutions' own {@code Representative.profileId} (an identity <b>Profile</b> public id, §6.4), but a
 * caller of {@link #ownsRepresentative} holds only the authenticated <b>account</b> public id. Mapping
 * account → profile is identity's to do; institutions resolves it through identity's published api port — a
 * sanctioned acyclic {@code institutions → identity} api edge (identity never calls institutions back),
 * never a reach into identity's repositories (ADR-0013 §1).</p>
 */
@Service
@Transactional(readOnly = true)
public class RepresentativeQueryService implements RepresentativeQueryApi {

    private final RepresentativeRepository representativeRepository;
    private final ProfileLookupApi profileLookupApi;

    /**
     * @param representativeRepository representative persistence port (existence guard + constituency/ward read +
     *                                 the {@code profileId} read behind the ownership guard).
     * @param profileLookupApi         identity's published account → profile id resolver (ADR-0013 §1), the first
     *                                 hop of {@link #ownsRepresentative}; never identity's repositories.
     */
    public RepresentativeQueryService(RepresentativeRepository representativeRepository,
                                      ProfileLookupApi profileLookupApi) {
        this.representativeRepository = representativeRepository;
        this.profileLookupApi = profileLookupApi;
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(UUID representativePublicId) {
        // A null id can never reference a real row — short-circuit to false (the port contract is
        // "missing referent ⇒ false, never throw"; the caller turns false into its own rejection shape).
        if (representativePublicId == null) {
            return false;
        }
        return representativeRepository.existsByPublicId(representativePublicId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> constituencyOf(UUID representativePublicId) {
        // CONSTITUENCY-mandate MPs hold a constituency; councillor/special-seats/nominated reps do not.
        Constituency constituency = require(representativePublicId).getConstituency();
        return Optional.ofNullable(constituency).map(Constituency::getPublicId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<UUID> wardOf(UUID representativePublicId) {
        // COUNCILLOR_WARD councillors / ward-execs hold a ward; constituency-MPs/special-seats/nominated
        // do not (F1, D13). A null ward ⇒ empty ⇒ the caller applies no ward electoral gate for this subject.
        Location ward = require(representativePublicId).getWard();
        return Optional.ofNullable(ward).map(Location::getPublicId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean ownsRepresentative(UUID accountPublicId, UUID representativePublicId) {
        // Total/deny-by-default: a null on either axis can never prove ownership (authorization predicate must
        // fail CLOSED, never throw — the caller renders a clean conflict-of-interest deny).
        if (accountPublicId == null || representativePublicId == null) {
            return false;
        }
        // Hop 1 (institutions, own data): load the representative; an absent/soft-deleted rep, or one with no
        // linked account yet (the "being onboarded" placeholder, profileId == null), can have no owner → false.
        UUID linkedProfileId = representativeRepository.findByPublicId(representativePublicId)
                .map(Representative::getProfileId)
                .orElse(null);
        if (linkedProfileId == null) {
            return false;
        }
        // Hop 2 (identity, via its published port): resolve the caller's ACCOUNT public id to their PROFILE
        // public id. A missing/soft-deleted (anonymised — PDPA erasure) account resolves to empty → false.
        // The ownership holds iff the caller's profile IS the representative's linked profile (the §6.4 link).
        return profileLookupApi.profileIdForAccount(accountPublicId)
                .map(linkedProfileId::equals)
                .orElse(false);
    }

    /** Loads a representative by public id or throws a localised {@code NOT_FOUND} (cannot scope a phantom). */
    private Representative require(UUID representativePublicId) {
        return representativeRepository.findByPublicId(representativePublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "institutions.representative.notFound", representativePublicId));
    }
}
