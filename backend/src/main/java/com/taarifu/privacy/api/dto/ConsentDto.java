package com.taarifu.privacy.api.dto;

import java.time.Instant;

/**
 * The privacy-center read view of one current consent decision (UC-A16; ADR-0016 §2).
 *
 * <p>Responsibility: the boundary projection of a live {@link com.taarifu.privacy.domain.model.Consent} row.
 * Carries the purpose, the current state, the policy version it was decided under, and the decision instant —
 * never an internal id and no PII (the subject is the authenticated caller).</p>
 *
 * @param purpose       the processing-purpose name.
 * @param state         the current consent state (GRANTED/WITHDRAWN).
 * @param policyVersion the policy/consent-text version the decision was made against.
 * @param decidedAt     when the current decision was made (UTC).
 */
public record ConsentDto(
        String purpose,
        String state,
        String policyVersion,
        Instant decidedAt) {
}
