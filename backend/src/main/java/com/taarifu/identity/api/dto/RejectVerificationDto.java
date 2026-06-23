package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Moderator rejection payload (Flow 3, VERIFICATION-DESIGN §5). The subject's tier is untouched.
 *
 * @param reasonCode the machine rejection reason (e.g. {@code EVIDENCE_UNREADABLE}, {@code NAME_MISMATCH}).
 * @param note       an optional operator note (never PII).
 */
public record RejectVerificationDto(
        @NotBlank(message = "identity.reasonCode.required")
        @Size(max = 64, message = "identity.reasonCode.tooLong") String reasonCode,

        @Size(max = 1000, message = "identity.note.tooLong") String note
) {
}
