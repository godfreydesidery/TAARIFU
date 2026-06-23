package com.taarifu.identity.api.dto;

import com.taarifu.identity.domain.model.enums.IdType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to submit a government ID for verification (Flow 2, VERIFICATION-DESIGN §4; D15).
 *
 * <p>The {@code idNo} is the most sensitive PII on the platform — it is field-encrypted at rest and
 * <b>never</b> logged (§18, S-4). It never appears in any response or audit row (only the blind-index
 * hash is referenced). {@code evidenceRef} is an object-store key from the (EI-8) upload endpoint —
 * never document bytes.</p>
 *
 * @param idType      the government ID document type (NIDA/voter/passport).
 * @param idNo        the document number (PII — encrypted on store, never logged).
 * @param fullName    the claimed full name to match against the ID (not persisted as new PII, not logged).
 * @param evidenceRef object-store key to the submitted evidence, or {@code null} (out-of-band review).
 */
public record SubmitVerificationDto(
        @NotNull(message = "identity.idType.required") IdType idType,

        @NotBlank(message = "identity.idNo.required")
        @Size(max = 64, message = "identity.idNo.tooLong") String idNo,

        @NotBlank(message = "identity.fullName.required")
        @Size(max = 240, message = "identity.fullName.tooLong") String fullName,

        @Size(max = 512, message = "identity.evidenceRef.tooLong") String evidenceRef
) {
}
