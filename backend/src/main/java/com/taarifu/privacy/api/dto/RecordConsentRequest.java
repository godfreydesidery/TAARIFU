package com.taarifu.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body to record (grant or withdraw) a consent decision for a purpose (UC-A16; ADR-0016 §2).
 *
 * <p>Responsibility: the boundary shape a citizen submits in the privacy center. The subject is <b>never</b>
 * in the body — it is bound from the authenticated principal ({@code CurrentUser.requirePublicId()}) so one
 * citizen can never record consent on behalf of another. {@code purpose} and {@code state} are validated
 * against the governed enums in the service (an unknown value yields a typed {@code BAD_REQUEST}).</p>
 *
 * @param purpose       the {@link com.taarifu.privacy.domain.model.enums.ConsentPurpose} name (required).
 * @param state         the {@link com.taarifu.privacy.domain.model.enums.ConsentState} name — GRANTED or
 *                      WITHDRAWN (required).
 * @param policyVersion the privacy-policy / consent-text version the decision was made against (required —
 *                      informed consent, PDPA).
 * @param source        the coarse capture channel ({@code WEB}/{@code APP}/{@code USSD}), or {@code null}.
 */
public record RecordConsentRequest(
        @NotBlank(message = "{privacy.consent.purpose.required}") String purpose,
        @NotBlank(message = "{privacy.consent.state.required}") String state,
        @NotBlank(message = "{privacy.consent.policyVersion.required}") String policyVersion,
        String source) {
}
