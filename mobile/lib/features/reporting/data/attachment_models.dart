/// Models for report attachments and the **pre-signed media-upload contract**
/// (EI-8) the report form depends on (US-3.1, PRD §10 Epic M3, §15).
///
/// WHY a documented contract object rather than an invented endpoint: the upload
/// flow crosses to the backend, so the mobile lane states exactly what it needs
/// and treats the endpoint as a seam until the backend engineer confirms it (no
/// fabricated endpoints — a known sin of the old codebase). The two-step,
/// pre-signed flow keeps large media OFF the JSON report body (data-frugal, and
/// the report POST stays small and idempotency-safe):
///
///   1. `POST /reports/attachments/presign` with `{ filename, contentType, size }`
///      → `{ uploadUrl, ref, headers? }` — a short-lived direct-to-object-store
///      PUT target plus the opaque `ref` the report will carry.
///   2. `PUT {uploadUrl}` with the bytes (resumable/chunked for big files on 2G).
///   3. File the report with `attachmentRefs: [ref, …]`; the backend scans the
///      object and links it to the case.
///
/// Until that endpoint is wired, the picker still works: a captured file is held
/// as a [PendingAttachment] and the form queues its **local-file ref** in the
/// draft's `attachmentRefs`, so the offline draft survives and the upload step is
/// a drop-in once `presign` lands — the seam is preserved, nothing is faked.
library;

/// A media item the citizen captured/selected but not yet uploaded.
///
/// It holds the **local** file path and lightweight metadata needed to request a
/// pre-signed URL. It never embeds bytes in the draft (PRD §18 — attachments are
/// referenced, not inlined); the bytes are read only at upload time.
class PendingAttachment {
  /// Creates a pending attachment.
  const PendingAttachment({
    required this.localPath,
    required this.filename,
    required this.contentType,
    this.sizeBytes,
    this.uploadedRef,
  });

  /// The on-device file path of the captured/selected media.
  final String localPath;

  /// The display/file name (e.g. `photo_1718900000.jpg`).
  final String filename;

  /// The MIME type (e.g. `image/jpeg`) sent to `presign` for validation.
  final String contentType;

  /// File size in bytes, when known (helps the backend reject oversize media).
  final int? sizeBytes;

  /// The opaque object-store ref once uploaded, or `null` if not yet uploaded.
  final String? uploadedRef;

  /// Whether this attachment has been uploaded and has a server [uploadedRef].
  bool get isUploaded => uploadedRef != null && uploadedRef!.isNotEmpty;

  /// The value the report should carry in `attachmentRefs` for this item.
  ///
  /// Prefers the uploaded object-store [uploadedRef]; before upload (endpoint
  /// absent / still offline) it falls back to a `local:` ref so the draft keeps
  /// the seam and the citizen sees their attachment listed. The repository
  /// resolves `local:` refs to real refs at sync time once `presign` exists.
  String get ref => isUploaded ? uploadedRef! : 'local:$localPath';

  /// Returns a copy with the uploaded [uploadedRef] set.
  PendingAttachment copyWith({String? uploadedRef}) => PendingAttachment(
    localPath: localPath,
    filename: filename,
    contentType: contentType,
    sizeBytes: sizeBytes,
    uploadedRef: uploadedRef ?? this.uploadedRef,
  );
}

/// The response of the pre-sign step (`POST /reports/attachments/presign`).
///
/// Modelled now so the upload data source can be written against a stable shape;
/// the backend engineer confirms the exact field names before it is called.
class PresignedUpload {
  /// Creates a pre-signed upload target.
  const PresignedUpload({
    required this.uploadUrl,
    required this.ref,
    this.headers = const {},
  });

  /// The short-lived URL to `PUT` the bytes to (direct to object storage).
  final String uploadUrl;

  /// The opaque ref the report carries in `attachmentRefs`.
  final String ref;

  /// Extra headers the storage provider requires on the `PUT`.
  final Map<String, String> headers;

  /// Parses a presign node.
  factory PresignedUpload.fromJson(Map<String, dynamic> json) => PresignedUpload(
    uploadUrl: json['uploadUrl'] as String? ?? '',
    ref: json['ref'] as String? ?? '',
    headers:
        (json['headers'] as Map?)?.map(
          (k, v) => MapEntry(k.toString(), v.toString()),
        ) ??
        const {},
  );
}

/// Where the citizen is capturing media from.
enum AttachmentSource {
  /// Take a new photo with the camera.
  camera,

  /// Pick an existing image from the gallery.
  gallery,
}
