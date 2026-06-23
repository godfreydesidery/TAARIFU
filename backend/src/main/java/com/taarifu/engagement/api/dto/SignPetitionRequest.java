package com.taarifu.engagement.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request DTO to sign a petition (PRD §12.2 UC-E03, US-9.2).
 *
 * <p>Responsibility: the optional comment + privacy choice a T3 signer may attach. The signer's identity
 * is taken from the authenticated principal ({@code CurrentUser}), <b>never</b> from the request body, so
 * a caller can only ever sign as themselves (the one-per-person + no-self-petition guards rely on this).
 * No token field exists or is read — the integrity fence forbids balance in the sign path (PRD §23.5).</p>
 *
 * @param comment        optional comment, or {@code null}.
 * @param publicSignature whether to show the signer publicly (default {@code false}/private — explicit
 *                        opt-in only, PDPA data-minimisation).
 */
public record SignPetitionRequest(
        @Size(max = 1000, message = "engagement.signature.comment.tooLong")
        String comment,

        boolean publicSignature
) {
}
