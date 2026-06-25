package com.taarifu.privacy.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.domain.model.AuditEvent;
import com.taarifu.common.domain.port.ClockPort;
import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.privacy.api.dto.ConsentDto;
import com.taarifu.privacy.api.dto.RecordConsentRequest;
import com.taarifu.privacy.domain.model.Consent;
import com.taarifu.privacy.domain.model.enums.ConsentPurpose;
import com.taarifu.privacy.domain.model.enums.ConsentState;
import com.taarifu.privacy.domain.repository.ConsentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConsentService} — the append-on-change consent ledger (PDPA, ADR-0016 §2).
 *
 * <p>Proves: (a) a new decision supersedes the prior current decision for the same purpose (the
 * append-on-change invariant — consent history is preserved, never overwritten); (b) every decision is
 * audited with {@link AuditEventType#PRIVACY_CONSENT_CHANGED} carrying {@code purpose:state} only (no PII);
 * (c) an unknown purpose/state name yields a typed {@code BAD_REQUEST}; (d) {@code hasActiveConsent} is
 * deny-by-default (only a current GRANTED is true). No Docker.</p>
 */
@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock
    private ConsentRepository consentRepository;
    @Mock
    private AuditEventService audit;

    private final UUID subject = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-25T10:00:00Z");
    private final ClockPort clock = () -> now;

    private ConsentService service() {
        return new ConsentService(consentRepository, audit, clock);
    }

    @Test
    void record_grant_persistsAndAudits_withPurposeStateOnly() {
        when(consentRepository.findBySubjectPublicIdAndPurposeAndSupersededFalse(
                subject, ConsentPurpose.BEHAVIOURAL_ANALYTICS)).thenReturn(Optional.empty());
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsentDto dto = service().record(subject,
                new RecordConsentRequest("BEHAVIOURAL_ANALYTICS", "GRANTED", "v1", "APP"));

        assertThat(dto.purpose()).isEqualTo("BEHAVIOURAL_ANALYTICS");
        assertThat(dto.state()).isEqualTo("GRANTED");
        assertThat(dto.decidedAt()).isEqualTo(now);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        AuditEvent ev = captor.getValue();
        assertThat(ev.getEventType()).isEqualTo(AuditEventType.PRIVACY_CONSENT_CHANGED);
        assertThat(ev.getActorPublicId()).isEqualTo(subject);
        assertThat(ev.getSubjectPublicId()).isEqualTo(subject);
        // reason is purpose:state only — never PII.
        assertThat(ev.getReasonCode()).isEqualTo("BEHAVIOURAL_ANALYTICS:GRANTED");
    }

    @Test
    void record_withdraw_supersedesPriorCurrentDecision_appendOnChange() {
        Consent prior = Consent.record(subject, ConsentPurpose.MARKETING_NOTIFICATIONS,
                ConsentState.GRANTED, "v1", "WEB", now.minusSeconds(3600));
        when(consentRepository.findBySubjectPublicIdAndPurposeAndSupersededFalse(
                subject, ConsentPurpose.MARKETING_NOTIFICATIONS)).thenReturn(Optional.of(prior));
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        service().record(subject, new RecordConsentRequest("MARKETING_NOTIFICATIONS", "WITHDRAWN", "v1", null));

        // The prior decision is marked superseded (append-on-change: history preserved, not overwritten).
        assertThat(prior.isSuperseded()).isTrue();
        // A NEW row is saved for the withdrawal.
        ArgumentCaptor<Consent> saved = ArgumentCaptor.forClass(Consent.class);
        verify(consentRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(ConsentState.WITHDRAWN);
    }

    @Test
    void record_unknownPurpose_throwsBadRequest() {
        assertThatThrownBy(() -> service().record(subject,
                new RecordConsentRequest("NOT_A_PURPOSE", "GRANTED", "v1", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void hasActiveConsent_isDenyByDefault() {
        // No decision recorded → not active.
        when(consentRepository.findBySubjectPublicIdAndPurposeAndSupersededFalse(
                subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER)).thenReturn(Optional.empty());
        assertThat(service().hasActiveConsent(subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER)).isFalse();

        // A current WITHDRAWN is not active either.
        Consent withdrawn = Consent.record(subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER,
                ConsentState.WITHDRAWN, "v1", null, now);
        when(consentRepository.findBySubjectPublicIdAndPurposeAndSupersededFalse(
                subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER)).thenReturn(Optional.of(withdrawn));
        assertThat(service().hasActiveConsent(subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER)).isFalse();

        // A current GRANTED is active.
        Consent granted = Consent.record(subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER,
                ConsentState.GRANTED, "v1", null, now);
        when(consentRepository.findBySubjectPublicIdAndPurposeAndSupersededFalse(
                subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER)).thenReturn(Optional.of(granted));
        assertThat(service().hasActiveConsent(subject, ConsentPurpose.DATA_SHARING_PRIVATE_RESPONDER)).isTrue();
    }
}
