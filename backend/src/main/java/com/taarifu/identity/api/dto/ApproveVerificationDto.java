package com.taarifu.identity.api.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Moderator approval payload (Flow 3, VERIFICATION-DESIGN §5).
 *
 * @param registeredWardPublicId for a <b>voter ID</b>, the ward it is registered to (the operator reads
 *                               it from the card); {@code null} falls back to the subject's primary ward.
 *                               Ignored for non-voter IDs. This is what sets the authoritative
 *                               {@code isElectoral} location (D13).
 * @param note                   an optional operator note (never PII).
 */
public record ApproveVerificationDto(
        UUID registeredWardPublicId,
        @Size(max = 1000, message = "identity.note.tooLong") String note
) {
}
