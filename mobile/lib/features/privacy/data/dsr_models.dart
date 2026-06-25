/// Wire models for the PDPA data-subject-request endpoints, mirroring the backend
/// `DsrDto` (`com.taarifu.privacy.api.dto`) and the `DsrType`/`DsrStatus` enums
/// (PRD §18 PDPA, §25.1; ADR-0016).
library;

/// The kind of data-subject right being exercised (backend `DsrType`).
///
/// Only the two rights Taarifu honours at launch. ACCESS = export my data;
/// ERASURE = delete my data (de-identification + tombstoning, not row deletion —
/// the civic record is kept de-identified).
enum DsrType {
  /// Right of access — export the personal data held about me.
  access,

  /// Right to erasure ("right to be forgotten") — delete my personal data.
  erasure;

  /// The backend `DsrType` name.
  String get apiName => switch (this) {
    DsrType.access => 'ACCESS',
    DsrType.erasure => 'ERASURE',
  };

  /// Maps a backend `DsrType` name to a [DsrType], defaulting to [access].
  static DsrType fromCode(String? code) =>
      code == 'ERASURE' ? DsrType.erasure : DsrType.access;
}

/// A tracked data-subject request (backend `DsrDto`).
///
/// References + status only — never PII (PRD §18). The subject is always the
/// authenticated caller (bound server-side from the token), so this is strictly
/// the citizen's own request.
class DataSubjectRequest {
  /// Creates a request snapshot.
  const DataSubjectRequest({
    required this.id,
    required this.type,
    required this.status,
    this.requestedAt,
    this.acknowledgedAt,
    this.completedAt,
    this.dueAt,
    this.legalHold = false,
    this.reasonCode,
  });

  /// The request's public id (UUID string).
  final String id;

  /// ACCESS or ERASURE.
  final DsrType type;

  /// The lifecycle status name (RECEIVED/ACKNOWLEDGED/IN_PROGRESS/COMPLETED/
  /// REJECTED/ON_HOLD).
  final String status;

  /// When received (UTC), or `null`.
  final DateTime? requestedAt;

  /// When acknowledged (UTC), or `null` if not yet.
  final DateTime? acknowledgedAt;

  /// When completed/closed (UTC), or `null`.
  final DateTime? completedAt;

  /// The completion SLA deadline (UTC), or `null`.
  final DateTime? dueAt;

  /// Whether a legal hold currently suspends fulfilment (an item under
  /// investigation is exempt from erasure until released — §25.1).
  final bool legalHold;

  /// The machine reason code (on hold/rejection), or `null`.
  final String? reasonCode;

  /// Whether this request has reached a terminal state (no further action).
  bool get isTerminal => status == 'COMPLETED' || status == 'REJECTED';

  /// Parses a `DsrDto` node.
  factory DataSubjectRequest.fromJson(Map<String, dynamic> json) =>
      DataSubjectRequest(
        id: json['publicId'] as String? ?? '',
        type: DsrType.fromCode(json['type'] as String?),
        status: json['status'] as String? ?? '',
        requestedAt: DateTime.tryParse(json['requestedAt'] as String? ?? ''),
        acknowledgedAt: DateTime.tryParse(
          json['acknowledgedAt'] as String? ?? '',
        ),
        completedAt: DateTime.tryParse(json['completedAt'] as String? ?? ''),
        dueAt: DateTime.tryParse(json['dueAt'] as String? ?? ''),
        legalHold: json['legalHold'] == true,
        reasonCode: json['reasonCode'] as String?,
      );
}
