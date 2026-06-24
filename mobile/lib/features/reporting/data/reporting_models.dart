/// Wire models for the reporting endpoints, mirroring the backend DTOs in
/// `com.taarifu.reporting.api.dto` exactly (ReportController, IssueCategory
/// Controller, PublicReportController).
///
/// Hand-written (no codegen) to match the foundation's lean convention; each
/// parses the `data` node the [ApiClient] hands it. Swahili civic framing is
/// preserved in doc comments where domain-facing.
library;

/// An issue category for the report picker (backend `IssueCategoryDto`).
///
/// The picker shows [name] (Swahili-first) and, at file time, the expected SLA
/// derived from [defaultSlaTtfrMinutes]. [sensitive] flags an anonymity-eligible
/// category (corruption/GBV — D-Q1); [forcePrivate] means the backend overrides
/// the citizen's visibility choice to PRIVATE.
class IssueCategory {
  /// Creates a category.
  const IssueCategory({
    required this.id,
    required this.name,
    this.parentId,
    this.icon,
    this.sensitive = false,
    this.forcePrivate = false,
    this.defaultVisibility,
    this.defaultSlaTtfrMinutes = 0,
  });

  /// Category public id (UUID string) — the file-report input.
  final String id;

  /// Swahili-first display name.
  final String name;

  /// Parent category id, or `null` for a top-level node.
  final String? parentId;

  /// Optional UI icon token.
  final String? icon;

  /// Whether this category is anonymity-eligible (sensitive).
  final bool sensitive;

  /// Whether reports here are forced PRIVATE regardless of the citizen's choice.
  final bool forcePrivate;

  /// The category's default visibility (`PUBLIC`/`PRIVATE`), or `null`.
  final String? defaultVisibility;

  /// Default time-to-first-response in minutes (shown to set expectations).
  final int defaultSlaTtfrMinutes;

  /// Parses a category node.
  factory IssueCategory.fromJson(Map<String, dynamic> json) => IssueCategory(
    id: json['id'] as String,
    name: json['name'] as String? ?? '',
    parentId: json['parentId'] as String?,
    icon: json['icon'] as String?,
    sensitive: json['sensitive'] == true,
    forcePrivate: json['forcePrivate'] == true,
    defaultVisibility: json['defaultVisibility'] as String?,
    defaultSlaTtfrMinutes:
        (json['defaultSlaTtfrMinutes'] as num?)?.toInt() ?? 0,
  );
}

/// A filed report in the reporter's own view (backend `ReportDto`).
///
/// Carries the human ticket [code] (`TAR-YYYY-NNNNNN`) the citizen tracks by,
/// the lifecycle [status], the SLA [dueAt], and the [resolution]/[confirmation]
/// for the confirm/dispute step (US-3.5).
class Report {
  /// Creates a report.
  const Report({
    required this.id,
    required this.code,
    required this.title,
    required this.status,
    this.categoryName,
    this.description,
    this.priority,
    this.visibility,
    this.dueAt,
    this.resolution,
    this.confirmation,
    this.anonymous = false,
    this.createdAt,
  });

  /// Report public id (UUID string).
  final String id;

  /// Human ticket code; also the anonymous tracking handle.
  final String code;

  /// Title.
  final String title;

  /// Lifecycle status name (NEW/ASSIGNED/IN_PROGRESS/RESOLVED/CLOSED…).
  final String status;

  /// Category display name, or `null`.
  final String? categoryName;

  /// Full description, or `null` (omitted in the list view).
  final String? description;

  /// Priority name, or `null`.
  final String? priority;

  /// Effective visibility name, or `null`.
  final String? visibility;

  /// SLA due instant, or `null`.
  final DateTime? dueAt;

  /// Resolution note once RESOLVED, or `null`.
  final String? resolution;

  /// Citizen confirmation: `null` pending, `true` confirmed, `false` disputed.
  final bool? confirmation;

  /// Whether filed without identity linkage (sensitive categories only).
  final bool anonymous;

  /// Filed instant (UTC), or `null`.
  final DateTime? createdAt;

  /// Whether the case is RESOLVED and still awaiting the citizen's confirm/dispute.
  bool get awaitingConfirmation =>
      status == 'RESOLVED' && confirmation == null;

  /// Parses a report node.
  factory Report.fromJson(Map<String, dynamic> json) => Report(
    id: json['id'] as String,
    code: json['code'] as String? ?? '',
    title: json['title'] as String? ?? '',
    status: json['status'] as String? ?? '',
    categoryName: json['categoryName'] as String?,
    description: json['description'] as String?,
    priority: json['priority'] as String?,
    visibility: json['visibility'] as String?,
    dueAt: DateTime.tryParse(json['dueAt'] as String? ?? ''),
    resolution: json['resolution'] as String?,
    confirmation: json['confirmation'] as bool?,
    anonymous: json['anonymous'] == true,
    createdAt: DateTime.tryParse(json['createdAt'] as String? ?? ''),
  );
}

/// A single timeline entry on a report (backend `CaseEventDto`).
///
/// [publicEvent] lets the UI style internal vs public notes; the reporter's own
/// timeline read already excludes internal events server-side.
class CaseEvent {
  /// Creates a case event.
  const CaseEvent({
    required this.id,
    required this.eventType,
    this.publicEvent = true,
    this.message,
    this.createdAt,
  });

  /// Event public id (UUID string).
  final String id;

  /// Event type name (e.g. STATUS_CHANGE, COMMENT, ASSIGNMENT).
  final String eventType;

  /// Whether a public event (vs internal-only).
  final bool publicEvent;

  /// Event body/description, or `null`.
  final String? message;

  /// Event instant (UTC), or `null`.
  final DateTime? createdAt;

  /// Parses an event node.
  factory CaseEvent.fromJson(Map<String, dynamic> json) => CaseEvent(
    id: json['id'] as String,
    eventType: json['eventType'] as String? ?? '',
    publicEvent: json['publicEvent'] != false,
    message: json['message'] as String?,
    createdAt: DateTime.tryParse(json['createdAt'] as String? ?? ''),
  );
}

/// A draft built in the report form, ready to file or queue offline.
///
/// This is the *input* the report cubit hands the repository; the repository
/// turns it into the backend `FileReportDto` body and attaches the idempotency
/// key. Visibility/anonymous are citizen *preferences* the backend may override
/// for sensitive/force-private categories.
class ReportDraft {
  /// Creates a draft.
  const ReportDraft({
    required this.categoryId,
    required this.title,
    required this.description,
    required this.wardId,
    this.latitude,
    this.longitude,
    this.visibility = 'PUBLIC',
    this.anonymous = false,
    this.attachmentRefs = const [],
  });

  /// Chosen category id (required).
  final String categoryId;

  /// Short title (required).
  final String title;

  /// Free-text description (required; voice-to-text feeds this field).
  final String description;

  /// Resolved ward id (required; minimum pin granularity).
  final String wardId;

  /// Optional incident latitude.
  final double? latitude;

  /// Optional incident longitude.
  final double? longitude;

  /// Visibility preference (`PUBLIC`/`PRIVATE`).
  final String visibility;

  /// Whether to file anonymously (honoured only for sensitive categories).
  final bool anonymous;

  /// Object-store refs to already-uploaded, scanned attachments.
  ///
  /// Before the pre-signed upload endpoint exists these may be `local:` refs
  /// (the captured file path) that the repository resolves to real refs at sync
  /// time — see [PendingAttachment] and `ReportRepository.uploadAttachment`.
  final List<String> attachmentRefs;

  /// Returns a copy carrying [refs] as the attachment references.
  ///
  /// Keeps the draft immutable while letting the form attach captured-media refs
  /// just before submit (the screen builds the draft without attachments).
  ReportDraft withAttachmentRefs(List<String> refs) => ReportDraft(
    categoryId: categoryId,
    title: title,
    description: description,
    wardId: wardId,
    latitude: latitude,
    longitude: longitude,
    visibility: visibility,
    anonymous: anonymous,
    attachmentRefs: refs,
  );

  /// Converts to the backend `FileReportDto` JSON body.
  Map<String, dynamic> toRequestBody() => {
    'categoryId': categoryId,
    'title': title,
    'description': description,
    'wardId': wardId,
    'latitude': ?latitude,
    'longitude': ?longitude,
    'visibility': visibility,
    'anonymous': anonymous,
    'attachmentRefs': attachmentRefs,
  };
}
