package com.taarifu.identity.application.service;

import com.taarifu.identity.api.dto.RecipientContact;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecipientContactService} — identity's implementation of the published
 * {@link com.taarifu.identity.api.RecipientContactApi} contact-resolution port (ADR-0013 §1; PRD §13 SMS/email,
 * EI-3/6).
 *
 * <p>Pins the rules the dispatch path depends on and that a reviewer must never see silently regress: a known
 * recipient resolves to their account's raw phone; the email is included <b>only when verified</b> (an
 * unverified/absent email is withheld so we never send to an unproven address — PDPA); and an unknown/null id
 * resolves to empty (deny-by-default), so a notification fan-out skips that recipient gracefully rather than
 * crashing. Mockito only, no database.</p>
 */
class RecipientContactServiceTest {

    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final String MSISDN = "+255712345678";
    private static final String EMAIL = "mwananchi@example.tz";

    private ProfileRepository profileRepository;
    private RecipientContactService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        service = new RecipientContactService(profileRepository);
    }

    /** A known recipient with a verified email resolves to BOTH the raw phone and the email. */
    @Test
    void contactFor_verifiedEmail_resolvesPhoneAndEmail() {
        stubProfile(MSISDN, EMAIL, true);

        Optional<RecipientContact> contact = service.contactFor(PROFILE_ID);

        assertThat(contact).isPresent();
        assertThat(contact.get().msisdn()).isEqualTo(MSISDN);
        assertThat(contact.get().email()).isEqualTo(EMAIL);
        assertThat(contact.get().hasMsisdn()).isTrue();
        assertThat(contact.get().hasEmail()).isTrue();
    }

    /** An UNVERIFIED email is withheld — the phone resolves, the email is null (never send to an unproven address). */
    @Test
    void contactFor_unverifiedEmail_withholdsEmail() {
        stubProfile(MSISDN, EMAIL, false);

        Optional<RecipientContact> contact = service.contactFor(PROFILE_ID);

        assertThat(contact).isPresent();
        assertThat(contact.get().msisdn()).isEqualTo(MSISDN);
        assertThat(contact.get().email()).isNull();
        assertThat(contact.get().hasEmail()).isFalse();
    }

    /** An account with no email on file resolves the phone only (verified flag is moot — there is nothing to send). */
    @Test
    void contactFor_noEmailOnAccount_resolvesPhoneOnly() {
        stubProfile(MSISDN, null, true);

        Optional<RecipientContact> contact = service.contactFor(PROFILE_ID);

        assertThat(contact).isPresent();
        assertThat(contact.get().email()).isNull();
        assertThat(contact.get().hasEmail()).isFalse();
    }

    /** Deny-by-default: an unknown profile id resolves to empty (the dispatcher skips SMS/email gracefully). */
    @Test
    void contactFor_unknownProfile_isEmpty() {
        when(profileRepository.findByPublicId(PROFILE_ID)).thenReturn(Optional.empty());

        assertThat(service.contactFor(PROFILE_ID)).isEmpty();
    }

    /** A null id resolves to empty without touching the repository (defensive deny-by-default). */
    @Test
    void contactFor_nullId_isEmpty() {
        assertThat(service.contactFor(null)).isEmpty();
    }

    /** Stubs the repository to return a profile whose account carries the given phone/email + verification flag. */
    private void stubProfile(String phone, String email, boolean emailVerified) {
        User user = mock(User.class);
        when(user.getPhone()).thenReturn(phone);
        when(user.getEmail()).thenReturn(email);
        Profile profile = mock(Profile.class);
        when(profile.getUser()).thenReturn(user);
        when(profile.isEmailVerified()).thenReturn(emailVerified);
        when(profileRepository.findByPublicId(PROFILE_ID)).thenReturn(Optional.of(profile));
    }
}
