package com.taarifu.identity.api.dto;

import java.util.UUID;

/**
 * Response after a verification submit / for the caller's own verification status (VERIFICATION-DESIGN §4).
 *
 * <p>Carries no PII: the {@code idNo} is never echoed (only the request reference + status). The caller
 * reads only their <b>own</b> status, never another citizen's (§18).</p>
 *
 * @param verificationPublicId the request's public id.
 * @param status               the request status (e.g. {@code PENDING}).
 */
public record VerificationStatusDto(UUID verificationPublicId, String status) {
}
