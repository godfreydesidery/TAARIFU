package com.taarifu.identity.application.service;

import com.taarifu.geography.domain.model.Constituency;
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
 * constituency to the target. It is {@code @Transactional(readOnly = true)} and reads only identity-owned
 * rows plus the geography {@link Constituency} entity already FK-snapshotted on the electoral location (no
 * re-resolution of the ward→constituency bridge needed — the pin stored the effective constituency).</p>
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
        if (userPublicId == null || constituencyPublicId == null) {
            return false;
        }
        Profile profile = profileRepository.findByUser_PublicId(userPublicId).orElse(null);
        if (profile == null) {
            return false;
        }
        // The single voter-ID-authoritative electoral location (D13). Absent ⇒ no binding electoral scope.
        ProfileLocation electoral = profileLocationRepository
                .findByProfileAndElectoralTrue(profile)
                .orElse(null);
        if (electoral == null) {
            return false;
        }
        Constituency constituency = electoral.getConstituency();
        // The constituency was snapshotted on the pin via the effective-dated bridge; unresolved ⇒ deny.
        return constituency != null && constituencyPublicId.equals(constituency.getPublicId());
    }
}
