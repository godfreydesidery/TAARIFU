package com.taarifu.institutions.application.service;

import com.taarifu.identity.api.ProfileLookupApi;
import com.taarifu.institutions.domain.model.Representative;
import com.taarifu.institutions.domain.repository.RepresentativeRepository;
import com.taarifu.institutions.test.EntityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RepresentativeQueryService#ownsRepresentative(UUID, UUID)} — the institutions-owned
 * account ↔ representative ownership query that backs the accountability right-of-reply conflict-of-interest
 * fence (PRD §10 US-6.2; D16). Mockito only.
 *
 * <p>Responsibility: proves the two-hop ownership resolution and its <b>fail-closed</b> contract (the load-bearing
 * security predicate): account → profile (identity {@link ProfileLookupApi}) → representative {@code profileId}
 * (institutions). The positive case proves a rep's own account owns them; the negative cases prove every way the
 * proof can fail — a {@code null} input, a phantom/soft-deleted rep, an unlinked rep, an unresolvable
 * (anonymised) account, and a <b>rival's</b> account — all return {@code false}. Each test would fail if the
 * guard short-circuited the wrong way (CLAUDE.md §10 — test the invariant, not the happy path).</p>
 */
class RepresentativeQueryServiceOwnershipTest {

    private RepresentativeRepository representativeRepository;
    private ProfileLookupApi profileLookupApi;
    private RepresentativeQueryService service;

    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID representativeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        representativeRepository = mock(RepresentativeRepository.class);
        profileLookupApi = mock(ProfileLookupApi.class);
        service = new RepresentativeQueryService(representativeRepository, profileLookupApi);
    }

    /** A representative linked to the given profile id (the §6.4 account link), with a fixed public id. */
    private Representative representativeLinkedTo(UUID linkedProfileId) {
        Representative rep = EntityTestSupport.newWithIds(Representative.class, 1L, representativeId);
        EntityTestSupport.set(rep, "profileId", linkedProfileId);
        return rep;
    }

    // -------------------------------------------------------------------------------------------------
    // The positive path: a rep's OWN account owns them (account → profile → the rep's linked profile).
    // -------------------------------------------------------------------------------------------------

    @Test
    void ownsRepresentative_whenCallersProfileIsTheRepsLinkedProfile_isTrue() {
        when(representativeRepository.findByPublicId(representativeId))
                .thenReturn(Optional.of(representativeLinkedTo(profileId)));
        when(profileLookupApi.profileIdForAccount(accountId)).thenReturn(Optional.of(profileId));

        assertThat(service.ownsRepresentative(accountId, representativeId)).isTrue();
    }

    // -------------------------------------------------------------------------------------------------
    // The fence: a RIVAL's account does NOT own this representative (the conflict-of-interest case).
    // -------------------------------------------------------------------------------------------------

    @Test
    void ownsRepresentative_whenCallersProfileIsADifferentProfile_isFalse() {
        UUID rivalProfileId = UUID.randomUUID();
        when(representativeRepository.findByPublicId(representativeId))
                .thenReturn(Optional.of(representativeLinkedTo(profileId)));
        // The caller resolves to a DIFFERENT profile than the rep's linked profile → not the owner.
        when(profileLookupApi.profileIdForAccount(accountId)).thenReturn(Optional.of(rivalProfileId));

        assertThat(service.ownsRepresentative(accountId, representativeId)).isFalse();
    }

    // -------------------------------------------------------------------------------------------------
    // Fail-closed: every unprovable case returns false, never throws — and short-circuits cheaply.
    // -------------------------------------------------------------------------------------------------

    @Test
    void ownsRepresentative_nullAccountId_isFalse_withoutTouchingEitherPort() {
        assertThat(service.ownsRepresentative(null, representativeId)).isFalse();
        // A null axis can never prove ownership — no repository or identity lookup is even attempted.
        verify(representativeRepository, never()).findByPublicId(any());
        verify(profileLookupApi, never()).profileIdForAccount(any());
    }

    @Test
    void ownsRepresentative_nullRepresentativeId_isFalse_withoutTouchingEitherPort() {
        assertThat(service.ownsRepresentative(accountId, null)).isFalse();
        verify(representativeRepository, never()).findByPublicId(any());
        verify(profileLookupApi, never()).profileIdForAccount(any());
    }

    @Test
    void ownsRepresentative_phantomOrSoftDeletedRepresentative_isFalse_withoutResolvingAccount() {
        // An absent/soft-deleted rep (@SQLRestriction excludes soft-deleted) has no owner; the identity hop is
        // never reached (short-circuit) — a non-existent referent fails closed, never throws.
        when(representativeRepository.findByPublicId(representativeId)).thenReturn(Optional.empty());

        assertThat(service.ownsRepresentative(accountId, representativeId)).isFalse();
        verify(profileLookupApi, never()).profileIdForAccount(any());
    }

    @Test
    void ownsRepresentative_repWithNoLinkedAccount_isFalse_withoutResolvingAccount() {
        // The "being onboarded" placeholder: a rep whose profileId is null can have no owner.
        when(representativeRepository.findByPublicId(representativeId))
                .thenReturn(Optional.of(representativeLinkedTo(null)));

        assertThat(service.ownsRepresentative(accountId, representativeId)).isFalse();
        verify(profileLookupApi, never()).profileIdForAccount(any());
    }

    @Test
    void ownsRepresentative_accountResolvesToNoProfile_isFalse() {
        // A missing/anonymised (PDPA-erased) account resolves to empty → not the owner (deny-by-default).
        when(representativeRepository.findByPublicId(representativeId))
                .thenReturn(Optional.of(representativeLinkedTo(profileId)));
        when(profileLookupApi.profileIdForAccount(accountId)).thenReturn(Optional.empty());

        assertThat(service.ownsRepresentative(accountId, representativeId)).isFalse();
    }
}
