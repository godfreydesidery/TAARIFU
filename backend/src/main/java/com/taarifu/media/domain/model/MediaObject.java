package com.taarifu.media.domain.model;

import com.taarifu.common.domain.model.BaseEntity;
import com.taarifu.media.domain.model.enums.ScanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * A single uploaded media object / attachment held behind a quarantine-then-serve flow
 * (PRD §21 EI-8, §18, ARCHITECTURE.md §4.2/§7).
 *
 * <p>Responsibility: the durable record of one binary stored in S3-compatible object storage — its
 * stable {@code objectKey}, declared metadata, owning resource, uploader, EXIF-strip state, and above
 * all its {@link ScanStatus}. The bytes live in object storage; this row is the index, the access-control
 * anchor, and the scan-state machine that decides whether a download URL may ever be issued.</p>
 *
 * <p><b>Host reference by id only (module boundary).</b> An attachment belongs to some host resource in
 * another module — a {@code Report}, a {@code Profile}'s avatar, verification evidence, an announcement.
 * This module must not import those modules (ARCHITECTURE.md §3.2), so the host is referenced by an
 * opaque {@code (ownerType, ownerId)} pair: a string discriminator plus the host's public {@code UUID}.
 * Visibility/ownership of the host is therefore enforced <b>by the host module</b> at attach/serve time
 * — the download path delegates to the host's visibility port (MF-2, e.g. reporting's
 * {@code ReportMediaAccessApi}). This row never grants access on its own.</p>
 *
 * <p><b>EXIF/geo stripping (privacy — EI-8, §18, A6).</b> Photos routinely embed GPS coordinates and
 * device identifiers in EXIF; serving them would leak a reporter's exact location even when the civic
 * Report's incident geo is captured (and access-controlled) separately. {@link #exifStripped} records
 * whether the privacy-stripping pass has run for this object. The strip worker runs on a CLEAN verdict
 * (see {@code MediaService.applyScanVerdict}): it scrubs the stored bytes and only then sets this flag,
 * and the download path refuses any handled image type whose flag is still {@code false}. So this flag
 * is the enforced proof — not merely a seam — that the scrub happened before any byte is served.</p>
 *
 * <p><b>WHY scan status gates serving, not uploading.</b> Per EI-8 the citizen's action (e.g. filing a
 * report) is never blocked by the scanner; the object is accepted into quarantine immediately and only
 * <i>delivery</i> is deferred until {@link ScanStatus#CLEAN}. This keeps the mobile path resilient to a
 * scanner outage (fail-safe, no data loss) while never serving unsafe bytes.</p>
 */
@Entity
@Table(name = "media_object", indexes = {
        // Hosts list their attachments by (ownerType, ownerId); the composite index serves that read.
        @Index(name = "ix_media_object_owner", columnList = "owner_type, owner_id"),
        // The scan/promote worker and ops dashboards filter by scan state (e.g. all PENDING/FAILED).
        @Index(name = "ix_media_object_scan_status", columnList = "scan_status"),
        // objectKey is the storage-side identity; lookups during scan callbacks go through it.
        @Index(name = "ix_media_object_key", columnList = "object_key", unique = true)
})
@SQLRestriction("deleted = false")
public class MediaObject extends BaseEntity {

    /**
     * Discriminator naming the kind of host resource this object is attached to (e.g. {@code REPORT},
     * {@code PROFILE_AVATAR}, {@code VERIFICATION_EVIDENCE}, {@code ANNOUNCEMENT}).
     *
     * <p>WHY a free string rather than an enum here: the set of attachment hosts spans several feature
     * modules this module must not depend on (ARCHITECTURE.md §3.2). Keeping it a validated string keeps
     * the boundary clean; the authoritative catalogue of valid types lives with the host modules and is
     * enforced at attach time. The one host that has wired its file-time attach gate (reporting, via
     * {@code media.api.MediaAttachmentApi#validateAndBind}) already rejects an object whose {@code ownerType}
     * does not match the host's expected type — so a {@code REPORT}-typed object can never bind to a non-report
     * host. PHASE-3: needs a published cross-module owner-type catalogue (a per-{@code ownerType} validation
     * port mirroring the {@code SubjectContentQueryApi} registry) for upfront validation of host types whose
     * owners do not yet publish an attach gate; until then an unmatched type simply never binds (it stays an
     * orphan a janitor purges) and is never served (the serve path is deny-by-default).</p>
     */
    @Column(name = "owner_type", nullable = false, length = 48)
    private String ownerType;

    /**
     * The public {@code UUID} of the host resource (e.g. a Report's {@code publicId}) this object is
     * attached to. Referenced by id only — never a JPA association into another module (boundary rule).
     *
     * <p><b>Nullable until bound (V121).</b> In the citizen attachment pipeline the evidence photo is
     * uploaded <i>before</i> the host resource exists: the client requests an upload URL, uploads the
     * bytes, and only then files the report carrying the media public ids. At upload time the host id is
     * therefore unknown, so this column is {@code null} until the host module <b>binds</b> the object to
     * the created resource via {@link #bindTo(UUID)} (the reporting module does this through the
     * {@code media.api} port at file time). An unbound, uploaded object is an orphan a janitor may purge.</p>
     */
    @Column(name = "owner_id")
    private UUID ownerId;

    /**
     * The stable storage key under which the bytes live in object storage (e.g.
     * {@code quarantine/2026/06/<uuid>}). Unique; it is the identity the {@code ObjectStore} and the
     * scan callback use. WHY server-generated (not the client filename): client names collide, leak
     * info, and are attacker-controlled; a server key is collision-free and path-traversal-safe.
     */
    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    /**
     * The original filename as supplied by the uploader, retained for display/download only. Never used
     * to build the storage key (see {@link #objectKey}) and never trusted as a path.
     */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /** The declared MIME content type (e.g. {@code image/jpeg}); used to set the served Content-Type. */
    @Column(name = "content_type", length = 128)
    private String contentType;

    /** The object size in bytes as declared/observed; used for quota and max-size enforcement. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
     * The malware-scan state machine that decides servability (EI-8). Stored as a STRING so the DB value
     * is human-readable and stable across enum reordering; a CHECK constraint in the migration mirrors
     * the enum (defense in depth).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 16)
    private ScanStatus scanStatus = ScanStatus.PENDING;

    /**
     * The {@code publicId} of the {@code Profile} that uploaded this object. Referenced by id only
     * (identity is an upstream module; we store the UUID, not an association, to keep this module's
     * tables free of cross-module FKs per the boundary rule).
     */
    @Column(name = "uploaded_by_profile_id")
    private UUID uploadedByProfileId;

    /**
     * {@code true} once the EXIF/geo-stripping privacy pass has run for this object (EI-8, §18). The
     * serve path may require this to be {@code true} for image types so no un-stripped photo is ever
     * delivered. Defaults {@code false} — an object is "not yet stripped" until proven otherwise.
     */
    @Column(name = "exif_stripped", nullable = false)
    private boolean exifStripped = false;

    /**
     * {@code true} once the client confirmed the bytes were PUT and the declared content-type/size
     * passed policy ({@code confirm} step, V121). The pre-signed PUT happens directly client<->store, so
     * the app never observes the upload finishing; the explicit confirm asserts "the bytes are there"
     * and is the point the allow-list/max-size are enforced. A never-confirmed object is a dangling
     * upload-intent and is never attachable or served. Defaults {@code false}.
     */
    @Column(name = "uploaded", nullable = false)
    private boolean uploaded = false;

    /** JPA requires a no-arg constructor; not for application use. */
    protected MediaObject() {
    }

    /**
     * Creates a new quarantined media object record at upload-request time (status {@link ScanStatus#PENDING}).
     *
     * @param ownerType           host-resource discriminator (e.g. {@code REPORT}).
     * @param ownerId             host resource public id.
     * @param objectKey           server-generated storage key (unique, quarantine-prefixed).
     * @param originalFilename    client-supplied display filename (may be {@code null}).
     * @param contentType         declared MIME type (may be {@code null}).
     * @param sizeBytes           declared size in bytes (may be {@code null}).
     * @param uploadedByProfileId uploader's profile public id.
     */
    public MediaObject(String ownerType, UUID ownerId, String objectKey, String originalFilename,
                       String contentType, Long sizeBytes, UUID uploadedByProfileId) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedByProfileId = uploadedByProfileId;
        this.scanStatus = ScanStatus.PENDING;
        this.exifStripped = false;
        this.uploaded = false;
    }

    /**
     * Applies a scanner verdict to this object.
     *
     * <p>WHY the transition is centralised here (not set field-by-field by callers): it keeps the
     * "only CLEAN is servable" invariant and the promote-on-clean intent in one auditable place, and
     * prevents an illegal hop such as INFECTED→CLEAN. A {@code CLEAN} verdict is what makes
     * {@link #isServable()} true.</p>
     *
     * @param verdict the scanner outcome ({@code CLEAN}/{@code INFECTED}/{@code FAILED}).
     */
    public void applyScanVerdict(ScanStatus verdict) {
        if (verdict == ScanStatus.PENDING) {
            throw new IllegalArgumentException("A scan verdict cannot be PENDING");
        }
        // Terminal INFECTED never flips back to CLEAN (safety); FAILED may be re-scanned to CLEAN later.
        if (this.scanStatus == ScanStatus.INFECTED) {
            return;
        }
        this.scanStatus = verdict;
    }

    /** Marks the EXIF/geo-stripping privacy pass as completed for this object (EI-8, §18). */
    public void markExifStripped() {
        this.exifStripped = true;
    }

    /**
     * Marks the object as confirmed-uploaded (bytes are in storage and declared content-type/size passed
     * policy). Called by {@code confirm}; idempotent (re-confirming is a no-op).
     */
    public void markUploaded() {
        this.uploaded = true;
    }

    /**
     * Binds this previously-unbound object to its host resource once it exists (V121).
     *
     * <p>WHY a distinct step: the photo is uploaded before the report is filed, so the host id is unknown
     * at upload time ({@link #ownerId} starts {@code null}); the host module supplies it at file time via
     * the {@code media.api} port. Binding is allowed once and only to the same host; re-binding to a
     * different host is rejected so an attacker cannot graft an object onto a different report.</p>
     *
     * @param hostPublicId the host resource public id (e.g. the filed report's {@code publicId}).
     * @throws IllegalStateException if already bound to a different host (single-bind guard).
     */
    public void bindTo(UUID hostPublicId) {
        if (this.ownerId != null && !this.ownerId.equals(hostPublicId)) {
            throw new IllegalStateException("Media object is already bound to a different host");
        }
        this.ownerId = hostPublicId;
    }

    /**
     * @return {@code true} only when this object may be served: it is scanned {@link ScanStatus#CLEAN}.
     *         WHY a method (not a status read at call sites): the servability rule lives in one place so
     *         the download path cannot accidentally serve a non-CLEAN object (EI-8 fail-safe).
     */
    public boolean isServable() {
        return this.scanStatus == ScanStatus.CLEAN;
    }

    /**
     * Severs the uploader linkage on a data-subject ERASURE (PRD §18, §25.1; ADR-0016 §5.6) — the object
     * record survives (its bytes belong to the host civic record it is attached to, whose own anonymisation
     * is that host module's concern) while its tie to the now-erased uploader is cut.
     *
     * <p>WHY null the uploader (not delete the row here): an attachment's lifecycle is owned by its host
     * resource — deleting the media row from this handler could orphan a host that still references it (the
     * reporting/host module decides whether the host civic record survives anonymised). The strongest
     * severing available to the media module on a DSR is to drop the personal <i>uploader</i> reference; the
     * EXIF/geo strip (EI-8) already removed embedded location PII from the bytes at promote time.
     * <b>Idempotent</b>: an object whose uploader is already {@code null} is a harmless no-op.</p>
     */
    public void severUploader() {
        this.uploadedByProfileId = null;
    }

    /** @return the host-resource discriminator. */
    public String getOwnerType() {
        return ownerType;
    }

    /** @return the host resource public id. */
    public UUID getOwnerId() {
        return ownerId;
    }

    /** @return the storage key under which the bytes live. */
    public String getObjectKey() {
        return objectKey;
    }

    /** @return the original display filename, or {@code null}. */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /** @return the declared MIME content type, or {@code null}. */
    public String getContentType() {
        return contentType;
    }

    /** @return the declared size in bytes, or {@code null}. */
    public Long getSizeBytes() {
        return sizeBytes;
    }

    /** @return the current scan status. */
    public ScanStatus getScanStatus() {
        return scanStatus;
    }

    /** @return the uploader's profile public id, or {@code null}. */
    public UUID getUploadedByProfileId() {
        return uploadedByProfileId;
    }

    /** @return {@code true} if the EXIF/geo-stripping pass has run. */
    public boolean isExifStripped() {
        return exifStripped;
    }

    /** @return {@code true} once the client confirmed the bytes were uploaded and policy was validated. */
    public boolean isUploaded() {
        return uploaded;
    }
}
