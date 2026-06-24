package com.taarifu.reporting.infrastructure.adapter;

import com.taarifu.media.api.MediaAttachmentApi;
import com.taarifu.reporting.domain.port.AttachmentValidator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The production {@link AttachmentValidator} adapter — delegates to the media module's public
 * {@code MediaAttachmentApi} command port (ARCHITECTURE.md §3.2, §4.3; ADR-0013 {@code api -> api}).
 *
 * <p>Responsibility: implements reporting's {@link AttachmentValidator} port by calling
 * {@link MediaAttachmentApi#validateAndBind} / {@link MediaAttachmentApi#attachmentsOf} with the
 * report-attachment owner type. This is the single seam where reporting touches media — through its
 * published api port, never its repositories/tables (ARCHITECTURE §4.3). The media module owns the
 * ownership/state invariants and the bind; reporting supplies only the report id, reporter, and refs.</p>
 *
 * <p>WHY an adapter in infrastructure: keeps the application service depending on the abstraction so it
 * unit-tests with a stub, and localises the one cross-module coupling to one replaceable class — the same
 * shape as {@link GeographyWardResolver}.</p>
 */
@Component
public class MediaAttachmentValidator implements AttachmentValidator {

    /** The media owner-type discriminator for report attachments (the one place reporting names it). */
    private static final String OWNER_TYPE_REPORT = "REPORT";

    private final MediaAttachmentApi mediaAttachmentApi;

    /**
     * @param mediaAttachmentApi the media module's public attachment command/query port.
     */
    public MediaAttachmentValidator(MediaAttachmentApi mediaAttachmentApi) {
        this.mediaAttachmentApi = mediaAttachmentApi;
    }

    /** {@inheritDoc} */
    @Override
    public void validateAndBind(UUID reportPublicId, UUID reporterProfileId, List<UUID> attachmentRefs) {
        mediaAttachmentApi.validateAndBind(OWNER_TYPE_REPORT, reportPublicId, reporterProfileId, attachmentRefs);
    }

    /** {@inheritDoc} */
    @Override
    public List<UUID> attachmentsOf(UUID reportPublicId) {
        return mediaAttachmentApi.attachmentsOf(OWNER_TYPE_REPORT, reportPublicId);
    }
}
