package com.taarifu.reporting.domain.port;

import java.util.List;
import java.util.UUID;

/**
 * The reporting module's port for validating and binding a report's media attachments (PRD §10 US-3.1;
 * ARCHITECTURE.md §3.2 cross-module via ports).
 *
 * <p>Responsibility: turns the citizen-supplied attachment public ids into a confirmed, owned, bound set
 * for the filed report — or rejects the filing. The reporting service depends on this <b>abstraction</b>,
 * not on the media module directly, so it stays unit-testable with a stub and the module boundary holds
 * (SOLID/DIP, CLAUDE.md §3) — mirroring how {@link WardResolver} fronts geography.</p>
 *
 * <p>The production adapter (in {@code reporting.infrastructure}) delegates to the media module's public
 * {@code MediaAttachmentApi} command port; reporting never reaches into media's tables (ARCHITECTURE §4.3).</p>
 */
public interface AttachmentValidator {

    /**
     * Validates that every attachment id is the reporter's own confirmed upload of the right kind, then
     * binds them to the just-filed report. All-or-nothing: any bad id rejects the whole filing (the file
     * transaction rolls back). A {@code null}/empty list is a no-op.
     *
     * @param reportPublicId    the just-filed report's public id to bind the attachments to.
     * @param reporterProfileId the filing citizen's profile public id (required uploader of each object),
     *                          or {@code null} for an anonymous filing (which may not carry account media).
     * @param attachmentRefs    the media object public ids the citizen supplied (may be {@code null}/empty).
     * @throws com.taarifu.common.error.ApiException with {@code BAD_REQUEST} if any id is unknown, not the
     *         reporter's, of the wrong type, not confirmed-uploaded, or already bound elsewhere.
     */
    void validateAndBind(UUID reportPublicId, UUID reporterProfileId, List<UUID> attachmentRefs);

    /**
     * Lists the media public ids currently bound to a report — for the read path to surface attachments.
     *
     * @param reportPublicId the report's public id.
     * @return the bound media public ids (never {@code null}; empty if none).
     */
    List<UUID> attachmentsOf(UUID reportPublicId);
}
