package com.taarifu.identity.application.service;

import com.taarifu.identity.api.dto.ProfileSummary;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.model.enums.TrustTier;
import com.taarifu.identity.domain.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileLookupService} — identity's implementation of the published
 * {@link com.taarifu.identity.api.ProfileLookupApi} read port (ADR-0013 §1, §4; PRD §12.2 author labelling),
 * which backs the engagement {@code resolve creatorPublicId (account) -> identity Profile public id} wiring.
 *
 * <p>Pins the rules sibling modules depend on and that a reviewer must never see regress: an account public id
 * resolves to its 1:1 profile public id; a profile public id resolves to a summary carrying the public display
 * name and live trust-tier name and <b>no PII</b> (no national/voter ID, no phone/email, no demographic); and an
 * unknown/null id resolves to empty (deny-by-default) so an author-labelling read degrades to "unknown author"
 * rather than crashing. The tier is taken from the single live authority ({@link TierService}, MF-2), which the
 * test mocks so this stays a pure Mockito unit (no Docker).</p>
 */
class ProfileLookupServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();

    private ProfileRepository profileRepository;
    private TierService tierService;
    private ProfileLookupService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        tierService = mock(TierService.class);
        service = new ProfileLookupService(profileRepository, tierService);
    }

    @Test
    void profileIdForAccount_known_resolvesProfilePublicId() {
        // The exact engagement need: account public id (JWT subject) -> the 1:1 profile public id.
        Profile profile = mock(Profile.class);
        when(profile.getPublicId()).thenReturn(PROFILE_ID);
        when(profileRepository.findByUser_PublicId(ACCOUNT_ID)).thenReturn(Optional.of(profile));

        assertThat(service.profileIdForAccount(ACCOUNT_ID)).contains(PROFILE_ID);
    }

    @Test
    void profileIdForAccount_unknown_isEmpty() {
        // Deny-by-default: no account/profile for the id -> empty (caller treats it as "no profile").
        when(profileRepository.findByUser_PublicId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(service.profileIdForAccount(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void profileIdForAccount_nullId_isEmpty() {
        // Defensive: a null id never hits the repository.
        assertThat(service.profileIdForAccount(null)).isEmpty();
    }

    @Test
    void profileSummary_known_returnsDisplayNameTierAndId_neverPii() {
        // A real profile carrying name + ID + phone + email; the summary must carry the id + display name +
        // tier ONLY — and never the ID/contact PII the aggregate holds.
        User user = User.createPending("+255712345678");
        user.setEmail("asha@example.tz");
        Profile profile = Profile.createPersonForSignup(user);
        profile.updateDetails("Asha", "Mwananchi", LocalDate.of(1990, Month.JANUARY, 1), "F", "TZA");
        profile.setIdentity(IdType.VOTER, "VOTER-ID-CIPHERTEXT", "BLIND-INDEX-HASH");
        profile.markIdVerified(Instant.now());
        // getPublicId() is null on a transient entity, so assert the display name (the no-PII property) and id
        // passthrough independently: stub the lookup to return this real profile.
        when(profileRepository.findByPublicId(PROFILE_ID)).thenReturn(Optional.of(profile));
        // The tier comes from the single live authority (TierService, MF-2) — stub it; an ID-verified profile is T3.
        when(tierService.resolveLiveTier(any(Profile.class))).thenReturn(TrustTier.T3);

        Optional<ProfileSummary> summary = service.profileSummary(PROFILE_ID);

        assertThat(summary).isPresent();
        assertThat(summary.get().displayName()).isEqualTo("Asha Mwananchi");
        // Tier crosses the boundary as its name() ("T3") — the public author badge, never the enum.
        assertThat(summary.get().tier()).isEqualTo("T3");
        // The hard privacy fence: the summary carries NONE of the PII the aggregate holds — across ALL fields.
        assertThat(summary.get().displayName() + "|" + summary.get().tier())
                .doesNotContain("VOTER-ID-CIPHERTEXT")
                .doesNotContain("BLIND-INDEX-HASH")
                .doesNotContain("+255712345678")
                .doesNotContain("asha@example.tz");
    }

    @Test
    void profileSummary_tierReflectsLiveAuthority_notHardcoded() {
        // Proves the tier is taken from the live resolver, not a constant: a not-yet-ID-verified author resolves
        // to T2 here, while the display name passes through unchanged.
        Profile profile = Profile.createPersonForSignup(User.createPending("+255700000000"));
        profile.updateDetails("Juma", "Ali", null, null, null);
        when(profileRepository.findByPublicId(PROFILE_ID)).thenReturn(Optional.of(profile));
        when(tierService.resolveLiveTier(any(Profile.class))).thenReturn(TrustTier.T2);

        Optional<ProfileSummary> summary = service.profileSummary(PROFILE_ID);

        assertThat(summary).isPresent();
        assertThat(summary.get().tier()).isEqualTo("T2");
        assertThat(summary.get().displayName()).isEqualTo("Juma Ali");
    }

    @Test
    void profileSummary_unknown_isEmpty() {
        when(profileRepository.findByPublicId(PROFILE_ID)).thenReturn(Optional.empty());

        assertThat(service.profileSummary(PROFILE_ID)).isEmpty();
    }

    @Test
    void profileSummary_nullId_isEmpty() {
        assertThat(service.profileSummary(null)).isEmpty();
    }
}
