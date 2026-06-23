package com.taarifu.responders.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request DTO to verify (or un-verify) a responder organisation — Moderator/Admin action (PRD §24.4).
 *
 * <p>Responsibility: validated input for {@code POST /organisations/{id}/verification}. Verification is
 * the §24.4 gate that lets a provider go live with a verified badge and guards against impersonation;
 * it is intentionally a <b>separate</b>, explicit action from editing so it can be independently
 * authorised and audited. {@code verified=false} revokes verification, immediately removing the org
 * from the public directory.</p>
 *
 * @param verified the new verification state (required).
 */
public record VerifyOrganisationRequest(
        @NotNull Boolean verified
) {
}
