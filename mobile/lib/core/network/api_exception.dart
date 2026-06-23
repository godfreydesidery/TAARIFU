/// Typed failures the API layer raises, so UI/BLoC code branches on a small,
/// stable set of cases instead of inspecting raw Dio errors.
///
/// WHY a sealed hierarchy: it lets the presentation layer exhaustively map a
/// failure to localised copy (offline vs timeout vs server vs a domain error
/// carrying a machine code) without leaking `Dio`/transport types upward
/// (clean boundaries, CLAUDE.md §3).
library;

import 'api_response.dart';

/// Base type for all API-layer failures.
sealed class ApiException implements Exception {
  /// Creates a failure with a developer-facing [message].
  const ApiException(this.message);

  /// A non-localised, developer-facing description (for logs, not the UI).
  final String message;

  @override
  String toString() => '$runtimeType: $message';
}

/// The device has no usable connectivity (caught before/at the request).
///
/// The UI maps this to the "you are offline; we will retry" copy and, for
/// mutations, the offline-draft/outbox path (PRD §15 offline-first).
class OfflineException extends ApiException {
  /// Creates an offline failure.
  const OfflineException([super.message = 'No connectivity']);
}

/// A connect/receive timeout — common on 2G/3G (PRD §15).
class TimeoutException extends ApiException {
  /// Creates a timeout failure.
  const TimeoutException([super.message = 'Network timeout']);
}

/// The server returned an error envelope (`success = false`).
///
/// Carries the backend's [statusCode] and the stable machine [error] so callers
/// can branch (e.g. `TIER_TOO_LOW`, `UNAUTHENTICATED`, `NOT_FOUND`). The
/// localised [serverMessage] is safe to show to the user.
class ApiErrorException extends ApiException {
  /// Creates a server-error failure from a decoded error envelope.
  ApiErrorException({
    required this.statusCode,
    required this.error,
    required this.serverMessage,
  }) : super('API error $statusCode: ${error.code}');

  /// The HTTP status the server reported.
  final int statusCode;

  /// The structured error (machine code + field errors).
  final ApiError error;

  /// The localised, user-safe message from the envelope.
  final String serverMessage;
}

/// Any other failure (5xx with no envelope, malformed body, unknown transport
/// error). The UI shows the generic "technical problem" copy.
class UnknownApiException extends ApiException {
  /// Creates an unknown failure.
  const UnknownApiException([super.message = 'Unknown error']);
}
