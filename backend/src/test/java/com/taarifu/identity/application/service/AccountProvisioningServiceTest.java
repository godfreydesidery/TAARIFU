package com.taarifu.identity.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountProvisioningService} — identity's implementation of the published
 * {@link com.taarifu.identity.api.AccountProvisioningApi} USSD account-provisioning command port
 * (ADR-0013 §4d; PRD §14, EI-4, US-0.1).
 *
 * <p>Pins the integrity rules a reviewer must never see silently regress on the {@code ussd → identity} seam:
 * provisioning is <b>idempotent</b> (a known MSISDN returns its existing account and NEVER creates a second —
 * D11/D15); an unknown MSISDN creates exactly one T1 account via the shared {@link AccountCreator} (DRY with
 * signup) and audits it; a blank phone is rejected; and the registered-ward read resolves the profile's
 * <b>primary</b> location ward (D12), deny-by-default at every missing link. Mockito only, no database.</p>
 */
class AccountProvisioningServiceTest {

    private static final String MSISDN = "+255712345678";

    private AccountCreator accountCreator;
    private UserRepository userRepository;
    private ProfileRepository profileRepository;
    private ProfileLocationRepository profileLocationRepository;
    private AuditEventService audit;
    private AccountProvisioningService service;

    @BeforeEach
    void setUp() {
        accountCreator = mock(AccountCreator.class);
        userRepository = mock(UserRepository.class);
        profileRepository = mock(ProfileRepository.class);
        profileLocationRepository = mock(ProfileLocationRepository.class);
        audit = mock(AuditEventService.class);
        service = new AccountProvisioningService(accountCreator, userRepository, profileRepository,
                profileLocationRepository, audit);
    }

    /** A known MSISDN returns its existing account and never creates a second one (one-per-phone, D11/D15). */
    @Test
    void ensureAccountByMsisdn_knownPhone_returnsExisting_andDoesNotCreate() {
        UUID existingId = UUID.randomUUID();
        User existing = userWithPublicId(existingId);
        when(userRepository.findByPhone(MSISDN)).thenReturn(Optional.of(existing));

        UUID result = service.ensureAccountByMsisdn(MSISDN);

        assertThat(result).isEqualTo(existingId);
        verify(accountCreator, never()).createCitizenAtT1(any());
        verify(audit, never()).record(any());
    }

    /** An unknown MSISDN creates exactly one T1 account via the shared creator and audits the provisioning. */
    @Test
    void ensureAccountByMsisdn_unknownPhone_createsT1Account_andAudits() {
        UUID newId = UUID.randomUUID();
        when(userRepository.findByPhone(MSISDN)).thenReturn(Optional.empty());
        when(accountCreator.createCitizenAtT1(MSISDN)).thenReturn(userWithPublicId(newId));

        UUID result = service.ensureAccountByMsisdn(MSISDN);

        assertThat(result).isEqualTo(newId);
        verify(accountCreator).createCitizenAtT1(MSISDN);
        verify(audit).record(any());
    }

    /** A leading/trailing space on the MSISDN is trimmed before the idempotency lookup. */
    @Test
    void ensureAccountByMsisdn_trimsPhone() {
        UUID existingId = UUID.randomUUID();
        when(userRepository.findByPhone(MSISDN)).thenReturn(Optional.of(userWithPublicId(existingId)));

        assertThat(service.ensureAccountByMsisdn("  " + MSISDN + " ")).isEqualTo(existingId);
    }

    /** A blank phone is a caller bug → BAD_REQUEST, never a created account. */
    @Test
    void ensureAccountByMsisdn_blankPhone_isRejected() {
        assertThatThrownBy(() -> service.ensureAccountByMsisdn("   "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service.ensureAccountByMsisdn(null))
                .isInstanceOf(ApiException.class);
        verify(accountCreator, never()).createCitizenAtT1(any());
    }

    /** The registered-ward read returns the profile's primary location ward (D12). */
    @Test
    void registeredWardId_returnsPrimaryLocationWard() {
        UUID accountId = UUID.randomUUID();
        UUID wardId = UUID.randomUUID();
        Profile profile = mock(Profile.class);
        Location ward = mock(Location.class);
        when(ward.getPublicId()).thenReturn(wardId);
        ProfileLocation primary = mock(ProfileLocation.class);
        when(primary.getWard()).thenReturn(ward);
        when(profileRepository.findByUser_PublicId(accountId)).thenReturn(Optional.of(profile));
        when(profileLocationRepository.findByProfileAndPrimaryTrue(profile)).thenReturn(Optional.of(primary));

        assertThat(service.registeredWardId(accountId)).contains(wardId);
    }

    /** Deny-by-default: no profile, no primary pin, or a null id all yield empty. */
    @Test
    void registeredWardId_missingLinks_isEmpty() {
        UUID accountId = UUID.randomUUID();
        when(profileRepository.findByUser_PublicId(accountId)).thenReturn(Optional.empty());
        assertThat(service.registeredWardId(accountId)).isEmpty();

        Profile profile = mock(Profile.class);
        when(profileRepository.findByUser_PublicId(accountId)).thenReturn(Optional.of(profile));
        when(profileLocationRepository.findByProfileAndPrimaryTrue(profile)).thenReturn(Optional.empty());
        assertThat(service.registeredWardId(accountId)).isEmpty();

        assertThat(service.registeredWardId(null)).isEmpty();
    }

    /** Builds a User with its public id set reflectively (BaseEntity assigns it on persist; tests bypass DB). */
    private static User userWithPublicId(UUID publicId) {
        User user = User.createPending(MSISDN);
        try {
            var field = Class.forName("com.taarifu.common.domain.model.BaseEntity")
                    .getDeclaredField("publicId");
            field.setAccessible(true);
            field.set(user, publicId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return user;
    }
}
