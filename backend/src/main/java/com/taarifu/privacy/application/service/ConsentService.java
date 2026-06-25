package com.taarifu.privacy.application.service;

import com.taarifu.common.audit.AuditEventService;
import com.taarifu.common.audit.AuditEventType;
import com.taarifu.common.audit.AuditOutcome;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The consent ledger application service — records versioned consent decisions and reads a subject's current
 * consents (PRD §18 PDPA 2022/2023, UC-A16, US-0.7; ADR-0016 §2/§7).
 *
 * <p>Responsibility: own the <b>append-on-change</b> invariant. Recording a decision for a (subject, purpose)
 * supersedes the prior current row (never edits it) and inserts a new live row, so the consent <b>history</b>
 * — the evidence PDPA requires — is fully reconstructable (§25.1 philosophy). Every decision is audited
 * ({@link AuditEventType#PRIVACY_CONSENT_CHANGED}, references/codes only — no PII). The subject is always the
 * authenticated caller; this service never accepts a body-supplied subject id.</p>
 *
 * <p>WHY consent reads are exposed here (not only on a future {@code ConsentQueryApi}): the privacy center is
 * self-service, so the controller reads the caller's own ledger directly. A cross-module synchronous
 * {@code ConsentQueryApi} (for the §24 private-responder share gate) is a documented CENTRAL NEED built when a
 * sibling needs it (ADR-0016 revisit (c)).</p>
 */
@Service
public class ConsentService {

    private final ConsentRepository consentRepository;
    private final AuditEventService audit;
    private final ClockPort clock;

    /**
     * @param consentRepository the append-on-change consent ledger store.
     * @param audit             append-only audit writer (records every consent change — L-1).
     * @param clock             time source for decision instants (testable).
     */
    public ConsentService(ConsentRepository consentRepository, AuditEventService audit, ClockPort clock) {
        this.consentRepository = consentRepository;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records a consent decision for the caller, superseding any prior current decision for the same purpose.
     *
     * @param subjectPublicId the authenticated caller's account public id (bound from the security context —
     *                        never a body field).
     * @param request         the purpose + state + policy version + optional source.
     * @return the new current decision as a {@link ConsentDto}.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if the purpose/state name is not a known enum value.
     */
    @Transactional
    public ConsentDto record(UUID subjectPublicId, RecordConsentRequest request) {
        ConsentPurpose purpose = parsePurpose(request.purpose());
        ConsentState state = parseState(request.state());

        // Append-on-change: mark the prior current decision (if any) superseded, then insert the new live row.
        consentRepository.findBySubjectPublicIdAndPurposeAndSupersededFalse(subjectPublicId, purpose)
                .ifPresent(Consent::markSuperseded);

        Consent decision = consentRepository.save(Consent.record(
                subjectPublicId, purpose, state, request.policyVersion(), request.source(), clock.now()));

        // Audit: who decided what for which purpose — references/codes only, never PII (PRD §18, L-1).
        audit.record(AuditEvent.Builder
                .of(AuditEventType.PRIVACY_CONSENT_CHANGED, AuditOutcome.SUCCESS)
                .actor(subjectPublicId).subject(subjectPublicId)
                .reason(purpose.name() + ":" + state.name())
                .build());

        return toDto(decision);
    }

    /**
     * Lists the caller's current (non-superseded) consent decisions across all purposes.
     *
     * @param subjectPublicId the authenticated caller's account public id.
     * @return one {@link ConsentDto} per decided purpose (never {@code null}; empty if nothing decided).
     */
    @Transactional(readOnly = true)
    public List<ConsentDto> listCurrent(UUID subjectPublicId) {
        return consentRepository.findBySubjectPublicIdAndSupersededFalse(subjectPublicId).stream()
                .map(ConsentService::toDto)
                .toList();
    }

    /**
     * Whether the subject currently holds an active (GRANTED, non-superseded) consent for a purpose — the
     * single truth a downstream gate (e.g. private-responder sharing) consults.
     *
     * @param subjectPublicId the account public id.
     * @param purpose         the processing purpose.
     * @return {@code true} only if the current decision for that purpose is GRANTED (deny-by-default: no
     *         decision, or a withdrawal, is {@code false}).
     */
    @Transactional(readOnly = true)
    public boolean hasActiveConsent(UUID subjectPublicId, ConsentPurpose purpose) {
        return consentRepository
                .findBySubjectPublicIdAndPurposeAndSupersededFalse(subjectPublicId, purpose)
                .map(c -> c.getState() == ConsentState.GRANTED)
                .orElse(false);
    }

    /** Maps a live consent row to its boundary DTO (the decision instant is grant- or withdraw-time). */
    private static ConsentDto toDto(Consent c) {
        return new ConsentDto(c.getPurpose().name(), c.getState().name(), c.getPolicyVersion(),
                c.getState() == ConsentState.GRANTED ? c.getGrantedAt() : c.getWithdrawnAt());
    }

    /** Parses a purpose name to the governed enum, throwing a typed BAD_REQUEST on an unknown value. */
    private static ConsentPurpose parsePurpose(String name) {
        try {
            return ConsentPurpose.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }

    /** Parses a state name to the governed enum, throwing a typed BAD_REQUEST on an unknown value. */
    private static ConsentState parseState(String name) {
        try {
            return ConsentState.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST);
        }
    }
}
