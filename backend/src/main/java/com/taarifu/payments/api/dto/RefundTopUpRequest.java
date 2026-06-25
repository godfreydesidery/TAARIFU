package com.taarifu.payments.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ADMIN request to refund a settled top-up or void an un-settled one (ADR-0015 addendum; PRD §18).
 *
 * <p>Responsibility: the validated input at the admin refund/void boundary. The acting administrator is the
 * authenticated caller (from the security context, never the body); the top-up is identified by the path id.
 * The body carries only the audit reason.</p>
 *
 * <p><b>Privacy (PRD §18):</b> {@link #reason} is a short, operator-supplied <b>machine/audit</b> reason
 * (e.g. {@code DUPLICATE_CHARGE}, {@code ADMIN_CANCELLED}); it is stored redacted and MUST NOT contain PII
 * (an MSISDN, a name, a national/voter ID). It is capped so it cannot smuggle a payload.</p>
 *
 * @param reason the required audit reason for the reversal (1–256 chars; no PII).
 */
public record RefundTopUpRequest(
        @NotBlank @Size(max = 256) String reason
) {
}
