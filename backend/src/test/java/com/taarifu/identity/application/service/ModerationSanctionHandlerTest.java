package com.taarifu.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.outbox.EventEnvelope;
import com.taarifu.identity.domain.model.User;
import com.taarifu.identity.domain.model.enums.UserStatus;
import com.taarifu.identity.domain.repository.UserRepository;
import com.taarifu.moderation.api.event.ModerationEventTypes;
import com.taarifu.moderation.api.event.ModerationSanctionApplied;
import com.taarifu.moderation.api.event.SanctionType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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
 * Unit tests for {@link ModerationSanctionHandler} — identity's consumer of the moderation account-sanction
 * outbox event (gap A5; ADR-0013 §2; ADR-0014 §4; PRD §18).
 *
 * <p>Pins the integrity rules a reviewer must never see silently regress on the {@code moderation → identity}
 * sanction seam: a {@code SUSPEND} suspends an {@code ACTIVE} account and audits it; the effect is
 * <b>idempotent</b> under at-least-once delivery (an already-{@code SUSPENDED} account is a no-op with NO
 * double-audit); an unknown account id is a tolerant no-op (the author may have been erased — §25.1) rather
 * than a DLQ; a {@code VERIFY_REQUEST} does not suspend; and a malformed payload surfaces an exception so the
 * relay records the failure. Mockito only, no database.</p>
 */
class ModerationSanctionHandlerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ModerationSanctionHandler handler =
            new ModerationSanctionHandler(userRepository, audit, objectMapper);

    @Test
    void registersForTheSanctionTaxonomyKey() {
        assertThat(handler.handledEventTypes())
                .containsExactly(ModerationEventTypes.MODERATION_SANCTION_APPLIED);
    }

    /** SUSPEND on an ACTIVE account → the account is suspended and a USER_SUSPENDED audit row is written. */
    @Test
    void suspend_onActiveAccount_suspendsAndAudits() {
        UUID accountId = UUID.randomUUID();
        User user = activeUser(accountId);
        when(userRepository.findByPublicId(accountId)).thenReturn(Optional.of(user));

        handler.handle(envelope(UUID.randomUUID(),
                new ModerationSanctionApplied(accountId, SanctionType.SUSPEND, Instant.now())));

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(userRepository).save(user);
        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(ev.capture());
        assertThat(ev.getValue().getEventType()).isEqualTo(AuditEventType.USER_SUSPENDED);
        assertThat(ev.getValue().getSubjectPublicId()).isEqualTo(accountId);
        assertThat(ev.getValue().getReasonCode()).isEqualTo("MODERATION_SANCTION");
    }

    /**
     * Idempotency (at-least-once delivery): a redelivered SUSPEND against an already-SUSPENDED account is a
     * no-op — no second save, no second audit. This is the test that fails if the idempotency guard is removed.
     */
    @Test
    void suspend_onAlreadySuspendedAccount_isNoOp_noDoubleAudit() {
        UUID accountId = UUID.randomUUID();
        User user = activeUser(accountId);
        user.suspend(); // already SUSPENDED (a prior delivery applied it)
        when(userRepository.findByPublicId(accountId)).thenReturn(Optional.of(user));

        handler.handle(envelope(UUID.randomUUID(),
                new ModerationSanctionApplied(accountId, SanctionType.SUSPEND, Instant.now())));

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(userRepository, never()).save(any());
        verify(audit, never()).record(any());
    }

    /** An unknown account id is a tolerant no-op (author may have been anonymised) — never a failure/DLQ. */
    @Test
    void suspend_onUnknownAccount_isNoOp() {
        UUID accountId = UUID.randomUUID();
        when(userRepository.findByPublicId(accountId)).thenReturn(Optional.empty());

        handler.handle(envelope(UUID.randomUUID(),
                new ModerationSanctionApplied(accountId, SanctionType.SUSPEND, Instant.now())));

        verify(userRepository, never()).save(any());
        verify(audit, never()).record(any());
    }

    /** VERIFY_REQUEST does NOT suspend the account (no account-state gate modelled yet) and writes no audit. */
    @Test
    void verifyRequest_doesNotSuspend() {
        UUID accountId = UUID.randomUUID();
        User user = activeUser(accountId);
        when(userRepository.findByPublicId(accountId)).thenReturn(Optional.of(user));

        handler.handle(envelope(UUID.randomUUID(),
                new ModerationSanctionApplied(accountId, SanctionType.VERIFY_REQUEST, Instant.now())));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository, never()).save(any());
        verify(audit, never()).record(any());
    }

    /** A payload missing the account id is a non-applicable defect → the relay must record the failure. */
    @Test
    void missingAccountId_throwsSoRelayRecordsFailure() {
        assertThatThrownBy(() -> handler.handle(envelope(UUID.randomUUID(),
                new ModerationSanctionApplied(null, SanctionType.SUSPEND, Instant.now()))))
                .isInstanceOf(IllegalStateException.class);
        verify(audit, never()).record(any());
    }

    /** Builds the JsonNode-payload envelope the relay delivers (ADR-0014 §3 — payload-agnostic tree). */
    private EventEnvelope<?> envelope(UUID eventId, ModerationSanctionApplied payload) {
        return new EventEnvelope<>(eventId, ModerationEventTypes.MODERATION_SANCTION_APPLIED,
                ModerationEventTypes.AGGREGATE_MODERATION_ITEM, UUID.randomUUID(),
                objectMapper.valueToTree(payload), payload.occurredAt());
    }

    /** Builds an ACTIVE User with its public id set reflectively (BaseEntity assigns it on persist). */
    private static User activeUser(UUID publicId) {
        User user = User.createPending("+255712000000");
        user.activate();
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
