package com.taarifu.media.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.media.api.MediaAttachmentApi;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implements the media module's {@link MediaAttachmentApi} cross-module port (ADR-0013 §4a) — the seam a
 * host module (e.g. {@code reporting}) uses to validate and bind the attachment references a citizen
 * supplied when filing.
 *
 * <p>Responsibility: enforce the attachment-ownership invariants in the owning module and bind the
 * validated objects to the host resource, all within the host's create transaction (so a validation
 * failure rolls the host write back). Returns ids only — never an entity (entities never leave the module;
 * the {@code ModuleBoundaryTest} forbids an entity in {@code ..api..}).</p>
 *
 * <p><b>The load-bearing rules (validate-and-bind):</b> a media id is attachable only if it exists, was
 * uploaded by the filing citizen (no cross-citizen grafting), is of the requested {@code ownerType}, has
 * been confirmed-uploaded (no dangling intent), and is not already bound to a different host (single-bind).
 * Any breach fails the whole batch with a localised {@link ErrorCode#BAD_REQUEST}.</p>
 *
 * <p>WHY a separate {@code @Service} (not folded into {@link MediaService}): keeps the cross-module command
 * port a small single-responsibility class with its own transaction semantics, mirroring
 * {@code tokens.TokenLedgerApiImpl}, and avoids {@link MediaService} importing the published {@code api}
 * interface it does not need for the controller use-cases (SRP, KISS).</p>
 */
@Service
@Transactional
public class MediaAttachmentApiImpl implements MediaAttachmentApi {

    private final MediaObjectRepository repository;

    /**
     * @param repository the media persistence port.
     */
    public MediaAttachmentApiImpl(MediaObjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public void validateAndBind(String ownerType, UUID ownerPublicId, UUID ownerProfileId,
                                List<UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return; // nothing to attach — a report with no evidence photos is valid.
        }
        if (ownerProfileId == null) {
            // An anonymous filing may not carry account-owned media — ownership cannot be proven.
            throw new ApiException(ErrorCode.BAD_REQUEST, "media.attach.anonymousNotAllowed");
        }
        LinkedHashSet<UUID> distinctIds = new LinkedHashSet<>(mediaIds);
        Map<UUID, MediaObject> byId = repository.findAllByPublicIdIn(distinctIds).stream()
                .collect(Collectors.toMap(MediaObject::getPublicId, Function.identity()));

        for (UUID id : distinctIds) {
            MediaObject media = byId.get(id);
            if (media == null) {
                // Unknown/soft-deleted id is an input-validation failure of THIS create (BAD_REQUEST).
                throw new ApiException(ErrorCode.BAD_REQUEST, "media.attach.unknown", String.valueOf(id));
            }
            if (!ownerProfileId.equals(media.getUploadedByProfileId())) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "media.attach.notOwner", String.valueOf(id));
            }
            if (!ownerType.equals(media.getOwnerType())) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "media.attach.wrongType", String.valueOf(id));
            }
            if (!media.isUploaded()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "media.attach.notUploaded", String.valueOf(id));
            }
            try {
                media.bindTo(ownerPublicId);
            } catch (IllegalStateException alreadyBoundElsewhere) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "media.attach.alreadyBound", String.valueOf(id));
            }
        }
        // Dirty-checked objects flush on commit; the host's transaction owns the boundary.
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<UUID> attachmentsOf(String ownerType, UUID ownerPublicId) {
        return repository.findByOwnerTypeAndOwnerId(ownerType, ownerPublicId).stream()
                .map(MediaObject::getPublicId)
                .toList();
    }
}
