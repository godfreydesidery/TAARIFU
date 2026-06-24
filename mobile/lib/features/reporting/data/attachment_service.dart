/// The camera/gallery capture seam (EI-8) behind a small, injectable interface.
///
/// WHY a seam, not the `image_picker` plugin yet: pulling an image picker +
/// camera permissions + its native deps grows the APK a low-bundle citizen must
/// download (PRD §15 data-budget), so we keep the *contract and the UX* real now
/// and land the plugin in the slice that ships media end-to-end. The report form
/// and its cubit depend only on this interface; swapping in an
/// `ImagePickerAttachmentService` is a one-line composition-root change with no
/// UI/cubit edits (clean boundaries, CLAUDE.md §3).
///
/// The default binding is [UnavailableAttachmentService]: the camera/gallery
/// buttons are live, but a tap reports [AttachmentUnavailable] so the UI shows an
/// honest "coming soon" message — it never fabricates a fake capture (no dummy
/// data, a known sin of the old codebase).
library;

import 'attachment_models.dart';

/// Thrown when capture is requested but no real picker is wired yet, or the
/// citizen cancelled / denied permission. Carries no PII.
class AttachmentUnavailable implements Exception {
  /// Creates the failure with a developer-facing [reason].
  const AttachmentUnavailable([this.reason = 'Attachment capture unavailable']);

  /// A non-localised reason for logs (the UI shows localised copy).
  final String reason;

  @override
  String toString() => 'AttachmentUnavailable: $reason';
}

/// Captures a [PendingAttachment] from the camera or gallery.
abstract interface class AttachmentService {
  /// Whether real capture is available on this build (drives UI affordances).
  bool get isAvailable;

  /// Captures one media item from [source], downscaled for low data where the
  /// implementation supports it. Throws [AttachmentUnavailable] if capture is
  /// not possible (no picker wired, cancelled, or permission denied).
  Future<PendingAttachment> capture(AttachmentSource source);
}

/// The default, dependency-free binding: capture is not available yet.
///
/// Keeps the seam honest — the form is fully built and tappable, but a capture
/// attempt surfaces a graceful, localised "coming soon" rather than faking media.
class UnavailableAttachmentService implements AttachmentService {
  /// Creates the unavailable service.
  const UnavailableAttachmentService();

  @override
  bool get isAvailable => false;

  @override
  Future<PendingAttachment> capture(AttachmentSource source) async =>
      throw const AttachmentUnavailable('image_picker not wired (EI-8)');
}
