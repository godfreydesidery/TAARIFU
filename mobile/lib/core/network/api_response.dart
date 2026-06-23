/// Typed model of the Taarifu backend's single response envelope and its error
/// payload, plus pagination metadata.
///
/// This mirrors the backend's `ApiResponse<T>` / `ApiError` / `PageMeta` records
/// exactly (backend `common.api.dto`, ARCHITECTURE.md §5). Keeping one typed
/// model here is the client-side equivalent of the backend's "single envelope"
/// rule (PRD §17): every screen consumes the same shape, so there is one place
/// to evolve when the contract changes.
library;

/// A typed wrapper over the backend envelope:
/// `{ success, statusCode, message, data, meta, timestamp }`.
///
/// On success [data] holds the decoded payload of type `T`; on error [data] is
/// `null` and the structured error lives in [error] (machine [ApiError.code] +
/// field [ApiError.errors]). Callers should branch on [success].
class ApiResponse<T> {
  /// Creates an envelope. Prefer [ApiResponse.fromJson].
  const ApiResponse({
    required this.success,
    required this.statusCode,
    required this.message,
    this.data,
    this.error,
    this.meta,
    this.timestamp,
  });

  /// Whether the request succeeded.
  final bool success;

  /// The integer HTTP status the server reported (200, 403, 404 …).
  ///
  /// WHY duplicated in the body: it lets a client read the outcome class without
  /// parsing the transport status line, matching the backend contract. The
  /// stable machine code is at [ApiError.code], not here (ADR-0008).
  final int statusCode;

  /// Localised, human-readable message (Swahili default, English secondary).
  ///
  /// This is safe to surface to users, but UI logic must branch on
  /// [ApiError.code] / [statusCode], never on this string (it is localised and
  /// may change).
  final String message;

  /// The decoded success payload, or `null` on error.
  final T? data;

  /// The structured error, or `null` on success.
  final ApiError? error;

  /// Pagination metadata for list endpoints, or `null` when not paginated.
  final PageMeta? meta;

  /// Server response instant (ISO-8601 UTC), or `null` if absent.
  final DateTime? timestamp;

  /// Decodes a raw envelope map into a typed [ApiResponse].
  ///
  /// [dataParser] converts the raw `data` JSON (a `Map`, a `List`, or `null`)
  /// into the typed payload `T` on success. On an error envelope `data` carries
  /// the [ApiError] shape (`{ code, errors? }`) and [dataParser] is not called.
  ///
  /// [json] the raw decoded envelope.
  /// [dataParser] success-payload decoder; receives the raw `data` node.
  factory ApiResponse.fromJson(
    Map<String, dynamic> json,
    T Function(Object? data) dataParser,
  ) {
    final success = json['success'] == true;
    final rawData = json['data'];
    return ApiResponse<T>(
      success: success,
      statusCode: (json['statusCode'] as num?)?.toInt() ?? 0,
      message: json['message'] as String? ?? '',
      data: success ? dataParser(rawData) : null,
      error: success ? null : ApiError.fromJson(rawData),
      meta: json['meta'] == null
          ? null
          : PageMeta.fromJson(json['meta'] as Map<String, dynamic>),
      timestamp: DateTime.tryParse(json['timestamp'] as String? ?? ''),
    );
  }
}

/// The structured error payload carried at `data` on an error envelope:
/// `{ code, errors? }` (backend `ApiError`).
///
/// [code] is the stable machine code clients branch on (e.g. `TIER_TOO_LOW`,
/// `NOT_FOUND`, `VALIDATION_FAILED`) — never branch on the localised message.
class ApiError {
  /// Creates an error payload.
  const ApiError({required this.code, this.errors = const []});

  /// The stable machine error code (backend `ErrorCode.name()`).
  final String code;

  /// Field-level validation failures; empty for non-validation errors.
  final List<ApiFieldError> errors;

  /// Decodes the raw `data` node of an error envelope.
  ///
  /// Tolerates a missing/malformed node by falling back to an `UNKNOWN` code so
  /// the client never crashes on an unexpected error shape.
  factory ApiError.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      return const ApiError(code: 'UNKNOWN');
    }
    final rawErrors = raw['errors'];
    return ApiError(
      code: raw['code'] as String? ?? 'UNKNOWN',
      errors: rawErrors is List
          ? rawErrors
                .whereType<Map<String, dynamic>>()
                .map(ApiFieldError.fromJson)
                .toList(growable: false)
          : const [],
    );
  }
}

/// A single field-level validation error (backend `ErrorDetail`).
class ApiFieldError {
  /// Creates a field error.
  const ApiFieldError({required this.field, required this.code, this.message});

  /// The offending request field (e.g. `phone`).
  final String field;

  /// The stable per-field machine code (e.g. `identity.phone.invalid`).
  final String code;

  /// An optional localised message for this field.
  final String? message;

  /// Decodes one `{ field, code, message }` element.
  factory ApiFieldError.fromJson(Map<String, dynamic> json) => ApiFieldError(
    field: json['field'] as String? ?? '',
    code: json['code'] as String? ?? '',
    message: json['message'] as String?,
  );
}

/// Pagination metadata (backend `PageMeta`): `{ page, size, total, totalPages }`.
class PageMeta {
  /// Creates page metadata.
  const PageMeta({
    required this.page,
    required this.size,
    required this.total,
    required this.totalPages,
  });

  /// Zero-based page index.
  final int page;

  /// Page size.
  final int size;

  /// Total element count across all pages.
  final int total;

  /// Total number of pages.
  final int totalPages;

  /// Whether a further page exists after [page].
  bool get hasMore => page + 1 < totalPages;

  /// Decodes a `meta` node.
  factory PageMeta.fromJson(Map<String, dynamic> json) => PageMeta(
    page: (json['page'] as num?)?.toInt() ?? 0,
    size: (json['size'] as num?)?.toInt() ?? 0,
    total: (json['total'] as num?)?.toInt() ?? 0,
    totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
  );
}
