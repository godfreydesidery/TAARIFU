package com.taarifu.media.api;

import java.util.List;
import java.util.UUID;

/**
 * The media module's <b>public, in-process command/query port</b> for host modules that attach media
 * (ADR-0013 §1, §4a). A host (e.g. {@code reporting}) calls this synchronously to validate the attachment
 * references a citizen supplied and to bind them to the host resource it just created, without importing
 * the media module's internals (ARCHITECTURE.md §3.2; ModuleBoundaryTest permits {@code api -> api}).
 *
 * <p>Responsibility: answer "are these media public ids real, owned by this citizen, of the right host
 * type, and confirmed-uploaded?" and — atomically with the host's create transaction — <b>bind</b> them to
 * the host resource's public id. The host treats the result as opaque truth and never reaches into
 * {@code media.domain}.</p>
 *
 * <h3>Privacy (PRD §18 / PDPA)</h3>
 * <p>The port carries only ids and a type discriminator — never bytes, the storage key, or any PII. It
 * performs no authorization of its own beyond the ownership/state checks; the host module remains
 * responsible for the citizen's tier/scope gate before filing.</p>
 */
public interface MediaAttachmentApi {

    /**
     * Validates that every supplied media id is attachable by {@code ownerProfileId} for {@code ownerType},
     * then binds each to {@code ownerPublicId}. All-or-nothing: if any id fails validation the whole call
     * throws and nothing is bound (the host's transaction rolls back).
     *
     * <p>An id is attachable iff it: exists (not soft-deleted); was uploaded by {@code ownerProfileId} (no
     * cross-citizen grafting); was created for the same {@code ownerType}; has been confirmed-uploaded; and
     * is not already bound to a <i>different</i> host. Re-binding an already-bound-to-this-host id is a
     * no-op (idempotent re-file). Duplicate ids are de-duplicated.</p>
     *
     * <p>WHY this does NOT require CLEAN scan state: per EI-8 the citizen's filing must never be blocked by
     * the asynchronous scanner — the report is filed and the attachment recorded immediately; only
     * <i>serving</i> the bytes is deferred until CLEAN (PRD §15, §21 EI-8).</p>
     *
     * @param ownerType      the host-resource discriminator the media must match (e.g. {@code REPORT}).
     * @param ownerPublicId  the just-created host resource's public id to bind the media to.
     * @param ownerProfileId the filing citizen's profile public id (required uploader of each object);
     *                       {@code null} is rejected — an anonymous filing may not carry account-owned media.
     * @param mediaIds       the media object public ids to validate and bind (may be empty/{@code null} -> no-op).
     * @throws com.taarifu.common.error.ApiException with {@code BAD_REQUEST} if any id is unknown, not the
     *         citizen's, of the wrong type, not confirmed-uploaded, or bound elsewhere.
     */
    void validateAndBind(String ownerType, UUID ownerPublicId, UUID ownerProfileId, List<UUID> mediaIds);

    /**
     * Lists the media public ids currently bound to a given host resource — for the host's read path.
     *
     * @param ownerType     the host-resource discriminator.
     * @param ownerPublicId the host resource's public id.
     * @return the bound media public ids (never {@code null}; empty if none).
     */
    List<UUID> attachmentsOf(String ownerType, UUID ownerPublicId);
}
