package com.taarifu.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A Moderator verification-queue row (Flow 3, VERIFICATION-DESIGN §5). References only — no PII.
 *
 * @param verificationPublicId the request public id (used to approve/reject).
 * @param subjectPublicId      the citizen being verified (a public id, never their name/{@code idNo}).
 * @param idType               the verification kind (always {@code ID} in this increment).
 * @param submittedAt          when the request was created.
 * @param evidenceRef          the object-store evidence key, or {@code null}.
 */
public record VerificationQueueItemDto(UUID verificationPublicId, UUID subjectPublicId, String idType,
                                       Instant submittedAt, String evidenceRef) {
}
