/// A single queued mutation in the [OutboxStore] — the unit of offline submit.
///
/// An entry is type-agnostic on purpose (it carries a `kind` + an opaque JSON
/// `payload`) so the same queue can later hold report comments, signatures, or
/// any other offline-safe write without changing the store. For the MVP slice the
/// only producer is the file-report flow (UC-D03).
library;

/// Lifecycle of a queued mutation.
enum OutboxStatus {
  /// Newly enqueued, not yet attempted (or queued because the device was offline).
  pending,

  /// Currently being sent (transient; guards against double-send within a flush).
  sending,

  /// The last send attempt failed transiently; eligible for retry.
  failed,
}

/// One pending mutation: a stable [localId], an idempotency [key], the [kind] of
/// operation, its JSON [payload], a [status], a retry [attempts] counter, and an
/// optional [lastError] code for the UI.
class OutboxEntry {
  /// Creates an entry.
  const OutboxEntry({
    required this.localId,
    required this.key,
    required this.kind,
    required this.payload,
    required this.createdAt,
    this.status = OutboxStatus.pending,
    this.attempts = 0,
    this.lastError,
  });

  /// The kind constant for a queued file-report submit.
  static const String kindReport = 'report.file';

  /// Stable local id (also the list key in the Drafts UI).
  final String localId;

  /// The client idempotency key sent on every (re)try so the backend de-dupes.
  ///
  /// This is what makes an offline draft safe to replay: the same key across
  /// retries means at most one ticket is ever created (PRD §17).
  final String key;

  /// The operation kind (e.g. [kindReport]).
  final String kind;

  /// The request body, as a JSON-encodable map.
  final Map<String, dynamic> payload;

  /// When the citizen created the draft (UTC) — preserves FIFO order.
  final DateTime createdAt;

  /// Current lifecycle status.
  final OutboxStatus status;

  /// How many send attempts have been made (for backoff/visibility).
  final int attempts;

  /// The last failure (a machine code or short message), or `null`.
  final String? lastError;

  /// Returns a copy with overrides (status transitions, retry bookkeeping).
  OutboxEntry copyWith({
    OutboxStatus? status,
    int? attempts,
    String? lastError,
    bool clearError = false,
  }) => OutboxEntry(
    localId: localId,
    key: key,
    kind: kind,
    payload: payload,
    createdAt: createdAt,
    status: status ?? this.status,
    attempts: attempts ?? this.attempts,
    lastError: clearError ? null : (lastError ?? this.lastError),
  );

  /// Serialises the entry (for the future disk-backed store + tests).
  Map<String, dynamic> toJson() => {
    'localId': localId,
    'key': key,
    'kind': kind,
    'payload': payload,
    'createdAt': createdAt.toIso8601String(),
    'status': status.name,
    'attempts': attempts,
    'lastError': lastError,
  };

  /// Parses an entry from its [toJson] form.
  factory OutboxEntry.fromJson(Map<String, dynamic> json) => OutboxEntry(
    localId: json['localId'] as String,
    key: json['key'] as String,
    kind: json['kind'] as String,
    payload: (json['payload'] as Map).cast<String, dynamic>(),
    createdAt:
        DateTime.tryParse(json['createdAt'] as String? ?? '') ?? DateTime.now(),
    status: OutboxStatus.values.firstWhere(
      (s) => s.name == json['status'],
      orElse: () => OutboxStatus.pending,
    ),
    attempts: (json['attempts'] as num?)?.toInt() ?? 0,
    lastError: json['lastError'] as String?,
  );
}
