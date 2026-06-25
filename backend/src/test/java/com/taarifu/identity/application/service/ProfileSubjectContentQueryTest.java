package com.taarifu.identity.application.service;

import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.moderation.api.FlagSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileSubjectContentQuery} — identity's implementation of moderation's
 * {@link com.taarifu.moderation.api.SubjectContentQueryApi} for {@link FlagSubjectType#PROFILE}
 * (US-12.3, UC-H05, EI-18, D-Q8; ADR-0018; ADR-0013 §4c). This closes the moderation auto-assist CENTRAL NEED
 * for the PROFILE subject type.
 *
 * <p>Pins the invariants the auto-assist screen depends on AND the load-bearing privacy invariant a security
 * reviewer must never see regress: the port serves {@code PROFILE}; a flagged profile resolves to its public
 * <b>display name</b> (the only moderator-actionable public text) — and the resolved content <b>NEVER</b>
 * contains the national/voter ID, phone, or email PII the profile/account also holds; a non-existent /
 * name-less / tombstoned profile resolves to empty so the screen is skipped and the item still reaches a human
 * (EI-18 floor). Mockito only; no Docker.</p>
 */
class ProfileSubjectContentQueryTest {

    private static final String FIRST = "Asha";
    private static final String LAST = "Mwananchi";
    private static final String ID_CIPHERTEXT = "VOTER-ID-CIPHERTEXT-NEVER-SCORED";
    private static final String ID_HASH = "BLIND-INDEX-HASH-NEVER-SCORED";
    private static final String PHONE = "+255712345678";
    private static final String EMAIL = "asha@example.tz";

    private ProfileRepository profileRepository;
    private ProfileSubjectContentQuery query;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        query = new ProfileSubjectContentQuery(profileRepository);
    }

    @Test
    void subjectType_isProfile() {
        // The registry key moderation dispatches on — identity owns exactly the PROFILE subject type.
        assertThat(query.subjectType()).isEqualTo(FlagSubjectType.PROFILE);
    }

    @Test
    void contentTextOf_existingProfile_returnsOnlyDisplayName_neverPii() {
        // A real profile carrying name + ID + phone + email. The screen must see the display name ONLY — the
        // single most important assertion in this module: no national/voter ID, phone, or email may reach the
        // scorer (an R10/R11/R12-grade leak otherwise — PRD §18, PDPA).
        UUID profileId = UUID.randomUUID();
        Profile profile = profileWithPii();
        when(profileRepository.findByPublicId(profileId)).thenReturn(Optional.of(profile));

        Optional<String> text = query.contentTextOf(profileId);

        assertThat(text).isPresent();
        assertThat(text.get()).isEqualTo(FIRST + " " + LAST);
        // The hard privacy fence: the resolved content carries NONE of the PII the aggregate holds.
        assertThat(text.get())
                .doesNotContain(ID_CIPHERTEXT)
                .doesNotContain(ID_HASH)
                .doesNotContain(PHONE)
                .doesNotContain(EMAIL)
                .doesNotContain("1990");        // date-of-birth year is never surfaced
    }

    @Test
    void contentTextOf_missingProfile_isEmpty_soScreenIsSkipped() {
        // A non-existent / soft-deleted (anonymised) profile resolves to empty — moderation then skips the
        // scorer and the flagged item still reaches a human moderator (EI-18 floor).
        UUID missing = UUID.randomUUID();
        when(profileRepository.findByPublicId(missing)).thenReturn(Optional.empty());

        assertThat(query.contentTextOf(missing)).isEmpty();
    }

    @Test
    void contentTextOf_profileWithNoNameSet_isEmpty() {
        // A profile mid-completion (no name) has no scorable public text → empty (screen skipped). Guards
        // against ever falling back to another field (e.g. PII) when the name is absent.
        UUID profileId = UUID.randomUUID();
        Profile nameless = Profile.createPersonForSignup(User.createPending(PHONE));
        when(profileRepository.findByPublicId(profileId)).thenReturn(Optional.of(nameless));

        assertThat(query.contentTextOf(profileId)).isEmpty();
    }

    @Test
    void contentTextOf_anonymisedProfile_returnsTombstoneLabel_neverResurrectedPii() {
        // After erasure the name holds the tombstone label and the ID is crypto-shredded. The screen sees the
        // tombstone, never resurrected PII (defence in depth — normally the soft-deleted row is hidden anyway).
        UUID profileId = UUID.randomUUID();
        Profile profile = profileWithPii();
        profile.anonymise("anonymized_user_deadbeef");
        when(profileRepository.findByPublicId(profileId)).thenReturn(Optional.of(profile));

        Optional<String> text = query.contentTextOf(profileId);

        assertThat(text).isPresent();
        assertThat(text.get()).isEqualTo("anonymized_user_deadbeef");
        assertThat(text.get())
                .doesNotContain(ID_CIPHERTEXT)
                .doesNotContain(ID_HASH)
                .doesNotContain(FIRST);
    }

    @Test
    void contentTextOf_nullId_isEmpty() {
        // Defensive: a null subject id never hits the repository and resolves to empty (no-op screen).
        assertThat(query.contentTextOf(null)).isEmpty();
    }

    /**
     * Builds a real, fully-populated person profile with name + (stand-in ciphertext) national/voter ID +
     * phone + verified email — so a test can prove the content port returns the display name and nothing else.
     */
    private Profile profileWithPii() {
        User user = User.createPending(PHONE);
        user.setEmail(EMAIL);
        Profile profile = Profile.createPersonForSignup(user);
        profile.updateDetails(FIRST, LAST, LocalDate.of(1990, 1, 1), "F", "TZA");
        profile.markEmailVerified();
        // The converter is bypassed in a pure unit test — these stand in for the encrypted ID + blind index.
        profile.setIdentity(IdType.VOTER, ID_CIPHERTEXT, ID_HASH);
        profile.markIdVerified(Instant.now());
        return profile;
    }
}
