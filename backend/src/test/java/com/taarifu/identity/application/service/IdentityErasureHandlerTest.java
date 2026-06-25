package com.taarifu.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.identity.domain.model.Profile;
import com.taarifu.identity.domain.model.ProfileLocation;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.IdType;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.ProfileLocationRepository;
import com.taarifu.identity.domain.repository.ProfileRepository;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.privacy.api.event.ErasureRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentityErasureHandler} — the IDENTITY crypto-shred + tombstone on erasure
 * (PRD §25.1, UC-A17/UC-S09; ADR-0016 §5).
 *
 * <p>Proves the load-bearing PDPA erasure invariants:</p>
 * <ul>
 *   <li><b>Crypto-shred:</b> the encrypted {@code idNo} AND the {@code idHash} blind index are nulled — the
 *       national/voter ID value and its dedup linkage are gone;</li>
 *   <li><b>Tombstone, not delete:</b> the account row persists (one-account permanence, D15) with PII severed,
 *       names replaced by {@code anonymized_user_*}, phone swapped to a non-reusable token, status DISABLED;</li>
 *   <li><b>ProfileLocations deleted</b> (§25.1);</li>
 *   <li><b>Audit hash-chain extended, never broken:</b> exactly one {@code IDENTITY_ERASED} row is APPENDED;
 *       no audit row is mutated/deleted;</li>
 *   <li><b>Idempotent:</b> a redelivery of an already-erased subject is a no-op (no second tombstone audit).</li>
 * </ul>
 * No Docker.
 */
@ExtendWith(MockitoExtension.class)
class IdentityErasureHandlerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ProfileRepository profileRepository;
    @Mock
    private ProfileLocationRepository profileLocationRepository;
    @Mock
    private AuditEventService audit;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID subject = UUID.randomUUID();
    private final UUID dsr = UUID.randomUUID();

    private IdentityErasureHandler handler() {
        return new IdentityErasureHandler(userRepository, profileRepository, profileLocationRepository,
                audit, objectMapper);
    }

    /** Builds the relay-style envelope carrying the ids-only erasure payload. */
    private EventEnvelope<?> event() {
        return new EventEnvelope<>(UUID.randomUUID(), ErasureRequested.EVENT_TYPE,
                ErasureRequested.AGGREGATE_TYPE, dsr, new ErasureRequested(subject, dsr),
                Instant.parse("2026-06-25T10:00:00Z"));
    }

    private User verifiedUser() {
        User user = User.createPending("+255700000001");
        user.activate();
        return user;
    }

    private Profile verifiedProfile(User user) {
        Profile profile = Profile.createPersonForSignup(user);
        profile.updateDetails("Asha", "Mwananchi", java.time.LocalDate.of(1990, 1, 1), "F", "TZA");
        // Encrypted id + blind index recorded (the converter is bypassed in a pure unit test — the value here
        // stands in for ciphertext; what matters is the field is non-null before erasure).
        profile.setIdentity(IdType.VOTER, "CIPHERTEXT", "BLIND_INDEX_HASH");
        profile.markIdVerified(Instant.now());
        return profile;
    }

    @Test
    void handle_cryptoShredsId_tombstonesAccount_deletesLocations_appendsAuditTombstone() {
        User user = verifiedUser();
        Profile profile = verifiedProfile(user);
        // A stand-in pin so the delete path has something to delete (its internals are irrelevant here).
        ProfileLocation pin = org.mockito.Mockito.mock(ProfileLocation.class);
        when(userRepository.findByPublicId(subject)).thenReturn(Optional.of(user));
        when(profileRepository.findByUser(user)).thenReturn(Optional.of(profile));
        when(profileLocationRepository.findByProfile(profile)).thenReturn(List.of(pin));

        handler().handle(event());

        // Crypto-shred: ID value + blind index gone.
        assertThat(profile.getIdNo()).isNull();
        assertThat(profile.getIdHash()).isNull();
        assertThat(profile.getIdType()).isNull();
        assertThat(profile.isIdVerified()).isFalse();
        assertThat(profile.isAnonymised()).isTrue();
        // Names tombstoned, demographics severed.
        assertThat(profile.getFirstName()).startsWith("anonymized_user_");
        assertThat(profile.getLastName()).isNull();
        assertThat(profile.getDateOfBirth()).isNull();

        // Account tombstoned (NOT deleted): phone swapped, status DISABLED, credentials/contacts gone.
        assertThat(user.getPhone()).startsWith("erased:");
        assertThat(user.getEmail()).isNull();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);

        // ProfileLocations deleted (§25.1).
        verify(profileLocationRepository).deleteAll(anyList());

        // Exactly one IDENTITY_ERASED tombstone APPENDED — the hash-chain is extended, never broken.
        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.IDENTITY_ERASED);
        assertThat(ev.getValue().getSubjectPublicId()).isEqualTo(subject);
        assertThat(ev.getValue().getReasonCode()).isEqualTo("DSR:" + dsr);
    }

    @Test
    void handle_redeliveryOfAlreadyErasedSubject_isIdempotentNoOp() {
        User user = verifiedUser();
        Profile profile = verifiedProfile(user);
        // Already anonymised by a prior delivery.
        profile.anonymise("anonymized_user_deadbeef");
        when(userRepository.findByPublicId(subject)).thenReturn(Optional.of(user));
        when(profileRepository.findByUser(user)).thenReturn(Optional.of(profile));

        handler().handle(event());

        // No second tombstone audit, no second location delete (idempotent at-least-once).
        verify(audit, never()).record(any(AuditEvent.class));
        verify(profileLocationRepository, never()).deleteAll(anyList());
    }

    @Test
    void handle_unknownSubject_isIdempotentNoOp() {
        when(userRepository.findByPublicId(subject)).thenReturn(Optional.empty());

        handler().handle(event());

        verify(audit, never()).record(any(AuditEvent.class));
        verify(profileLocationRepository, never()).deleteAll(anyList());
    }
}
