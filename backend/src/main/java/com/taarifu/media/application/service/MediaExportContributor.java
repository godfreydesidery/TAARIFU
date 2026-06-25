package com.taarifu.media.application.service;

import com.taarifu.media.api.dto.MediaExportView;
import com.taarifu.media.domain.model.MediaObject;
import com.taarifu.media.domain.repository.MediaObjectRepository;
import com.taarifu.privacy.api.SubjectExportContributor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Media's contribution to a data-subject ACCESS export — the metadata of objects the subject uploaded
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: implements the privacy module's {@link SubjectExportContributor} SPI so the privacy
 * export aggregator can include media's data <b>without</b> reaching into media's internals (ADR-0013).
 * Registered automatically as a Spring bean; the privacy {@code SubjectDataExportService} injects every
 * contributor and composes the export by {@link #section()}.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, ADR-0016 §4):</b> returns {@link MediaExportView} — object metadata
 * only (filename, type, size, scan state), never the bytes and never the internal storage key. Serving the
 * actual bytes is the access-controlled download path's job, never an export.</p>
 */
@Service
public class MediaExportContributor implements SubjectExportContributor {

    /** The export section key media fills. */
    private static final String SECTION = "media";

    private final MediaObjectRepository mediaObjectRepository;

    /**
     * @param mediaObjectRepository the subject's uploaded objects.
     */
    public MediaExportContributor(MediaObjectRepository mediaObjectRepository) {
        this.mediaObjectRepository = mediaObjectRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String section() {
        return SECTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the subject's uploaded-object metadata, or {@code null} if the subject uploaded nothing (so
     * the section is simply absent from the export).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Object contribute(UUID subjectPublicId) {
        List<MediaObject> objects = mediaObjectRepository.findByUploadedByProfileId(subjectPublicId);
        if (objects.isEmpty()) {
            return null;
        }
        List<MediaExportView.UploadedObject> items = objects.stream()
                .map(o -> new MediaExportView.UploadedObject(
                        o.getPublicId(),
                        o.getOriginalFilename(),
                        o.getContentType(),
                        o.getSizeBytes(),
                        o.getScanStatus().name(),
                        o.getCreatedAt()))
                .toList();
        return new MediaExportView(items);
    }
}
