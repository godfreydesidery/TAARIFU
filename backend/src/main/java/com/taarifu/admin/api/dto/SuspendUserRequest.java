package com.taarifu.admin.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request to suspend an account (M14, US-14.1).
 *
 * <p>Responsibility: carries an optional machine reason code for the audit trail; the target account is
 * the path id and the acting admin is the authenticated caller (never a body field). The reason is a
 * machine token (never free-form PII) so it is safe to write to the immutable audit log (PRD §18).</p>
 *
 * @param reasonCode an optional machine reason for the suspension (e.g. {@code POLICY_VIOLATION},
 *                   {@code SECURITY}); may be {@code null}/blank.
 */
public record SuspendUserRequest(@Size(max = 64) String reasonCode) {
}
