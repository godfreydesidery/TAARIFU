package com.taarifu.identity.application.service;

import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.identity.api.ElectoralScopeApi;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Live implementation of {@link ElectoralScopeApi} — the identity-owned resolution of a citizen's binding
 * electoral scope (ADR-0013 §1; D13, PRD §9.0).
 *
 * <p>Responsibility: resolve the actor's account public id to its {@code Profile}, read the profile's single
 * voter-ID-authoritative {@code isElectoral} {@code ProfileLocation}, and compare that location's derived
 * constituency (MP tier, {@link #isElectorOf}) or its pinned ward (councillor tier, {@link #isElectorOfWard}
 * — F1) to the target. It is {@code @Transactional(readOnly = true)} and reads only identity-owned rows plus
 * the geography {@link Constituency}/{@link Location} entities already FK-referenced on the electoral
 * location (no re-resolution of the ward→constituency bridge needed — the pin stored both the ward and the
 * effective constituency).</p>
 *
 * <p><b>Fence (D18, §23.5):</b> no token/wallet collaborator is injected or referenced — this is the
 * electoral-scope half of the binding-action fence and must never touch a balance. Every unresolved link
 * is deny-by-default ({@code false}), so a caller can never be falsely treated as an elector.</p>
 *
 * <p>WHY the grain is the <b>account</b> public id (not a profile id): the caller passes
 * {@code CurrentUser.requirePublicId()} (the JWT subject = {@code app_user.publicId}); this service maps it
 * to the profile via {@link ProfileRepository#findByUser_PublicId(UUID)} so the electoral check is keyed off
 * the authenticated principal, never a body-supplied id.</p>
 */
@Service
@Transactional(readOnly = true)
public class ElectoralScopeService implements ElectoralScopeApi {

    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;

    /**
     * @param profileRepository         resolves the account public id → its {@code Profile}.
     * @param profileLocationRepository reads the profile's single {@code isElectoral} location (D13).
     */
    public ElectoralScopeService(ProfileRepository profileRepository,
                                 ProfileLocationRepository profileLocationRepository) {
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isElectorOf(UUID userPublicId, UUID constituencyPublicId) {
        // Deny-by-default: a null target constituency, or any missing link, is never an elector match.
        if (constituencyPublicId == null) {
            return false;
        }
        ProfileLocation electoral = electoralLocationOf(userPublicId);
        if (electoral == null) {
            return false;
        }
        Constituency constituency = electoral.getConstituency();
        // The constituency was snapshotted on the pin via the effective-dated bridge; unresolved ⇒ deny.
        return constituency != null && constituencyPublicId.equals(constituency.getPublicId());
    }

    /** {@inheritDoc} */
    @Override
    public boolean isElectorOfWard(UUID userPublicId, UUID wardPublicId) {
        // Deny-by-default (F1): a null target ward, or any missing link, is never an elector match.
        if (wardPublicId == null) {
            return false;
        }
        ProfileLocation electoral = electoralLocationOf(userPublicId);
        if (electoral == null) {
            return false;
        }
        // The pinned ward is the minimum-granularity unit on the electoral location (PRD §9.0); it is a
        // required FK (never null on a persisted pin), but stay deny-by-default if absent for any reason.
        Location ward = electoral.getWard();
        return ward != null && wardPublicId.equals(ward.getPublicId());
    }

    /**
     * Resolves the user's single voter-ID-authoritative {@code isElectoral} {@code ProfileLocation}, or
     * {@code null} if there is no profile / no electoral location (deny-by-default at every missing link,
     * D13). Shared by {@link #isElectorOf} (constituency tier) and {@link #isElectorOfWard} (ward tier, F1).
     *
     * @param userPublicId the actor's account public id (the JWT subject), or {@code null}.
     * @return the single electoral location, or {@code null} if unresolved.
     */
    private ProfileLocation electoralLocationOf(UUID userPublicId) {
        if (userPublicId == null) {
            return null;
        }
        Profile profile = profileRepository.findByUser_PublicId(userPublicId).orElse(null);
        if (profile == null) {
            return null;
        }
        // The single voter-ID-authoritative electoral location (D13). Absent ⇒ no binding electoral scope.
        return profileLocationRepository.findByProfileAndElectoralTrue(profile).orElse(null);
    }
}
