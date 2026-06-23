package com.taarifu.identity.application.service;

import com.taarifu.common.security.TierResolver;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The live trust-tier resolver — the keystone of MF-2 (AUTH-DESIGN §7.1, ADR-0011 §3).
 *
 * <p>Responsibility: computes a caller's <b>current</b> tier T0–T3 from <b>live database state</b> on
 * every gated request, applying the PRD §7.3 predicates highest-first. This is the single authority for
 * {@code @RequiresTier}; the JWT {@code trustTier} claim is a UI hint and is never consulted. It also
 * recomputes and persists the cached {@link User#getTrustTier()} during signup/profile-completion so the
 * next issued token carries an accurate hint — but the cache is never trusted for gating.</p>
 *
 * <p>Predicates (AUTH-DESIGN §7.1):</p>
 * <ul>
 *   <li><b>T3</b> = T2 ∧ {@code profile.idVerified}.</li>
 *   <li><b>T2</b> = T1 ∧ first+last name present ∧ ≥1 {@code ProfileLocation} ∧ (email- or phone-verified).</li>
 *   <li><b>T1</b> = account ACTIVE ∧ (phone- or email-verified).</li>
 *   <li><b>T0</b> = otherwise (guest / PENDING / SUSPENDED / DISABLED — deny-by-default for gating).</li>
 * </ul>
 *
 * <p>Downgrade-aware (§25.5): if {@code idVerified} is later revoked, the resolver immediately returns
 * T2 again — new T3 actions are blocked with no token reissue, because the token tier is never trusted.</p>
 */
@Service
public class TierService implements TierResolver {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final ProfileLocationRepository profileLocationRepository;

    /**
     * @param userRepository            account lookup.
     * @param profileRepository         profile lookup (verification flags, names).
     * @param profileLocationRepository location count (the T2 ≥1-pin predicate).
     */
    public TierService(UserRepository userRepository,
                       ProfileRepository profileRepository,
                       ProfileLocationRepository profileLocationRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.profileLocationRepository = profileLocationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read-only and side-effect-free: a cache miss recomputes from the DB; the resolver is the only
     * authority (MF-2). A short Redis cache MAY front this later (≤30s staleness, AUTH-DESIGN §7.1) — it
     * would be a latency optimization, never an authority bypass.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public int resolveLiveTierRank(UUID userPublicId) {
        return resolveLiveTier(userPublicId).ordinal();
    }

    /**
     * Resolves the live {@link TrustTier} enum (used by signup/profile services to refresh the cache).
     *
     * @param userPublicId the account public id.
     * @return the current tier; T0 for an unknown/suspended/deleted account (deny-by-default).
     */
    @Transactional(readOnly = true)
    public TrustTier resolveLiveTier(UUID userPublicId) {
        Optional<User> userOpt = userRepository.findByPublicId(userPublicId);
        if (userOpt.isEmpty()) {
            return TrustTier.T0;
        }
        User user = userOpt.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            return TrustTier.T0;
        }
        Optional<Profile> profileOpt = profileRepository.findByUser(user);
        if (profileOpt.isEmpty()) {
            return TrustTier.T0;
        }
        Profile profile = profileOpt.get();
        return computeTier(profile);
    }

    /**
     * Resolves the live tier from an already-loaded {@link Profile} (avoids a re-query in the
     * signup/profile transaction). The account is assumed ACTIVE by the caller in those flows.
     *
     * @param profile the account's profile.
     * @return the current tier.
     */
    @Transactional(readOnly = true)
    public TrustTier resolveLiveTier(Profile profile) {
        return computeTier(profile);
    }

    /** Applies the T0–T3 predicates highest-first against live profile state. */
    private TrustTier computeTier(Profile profile) {
        boolean t1 = profile.isPhoneVerified() || profile.isEmailVerified();
        if (!t1) {
            return TrustTier.T0;
        }
        boolean profileComplete =
                isPresent(profile.getFirstName())
                        && isPresent(profile.getLastName())
                        && !profileLocationRepository.findByProfile(profile).isEmpty();
        boolean t2 = profileComplete && (profile.isEmailVerified() || profile.isPhoneVerified());
        if (!t2) {
            return TrustTier.T1;
        }
        if (profile.isIdVerified()) {
            return TrustTier.T3;
        }
        return TrustTier.T2;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
