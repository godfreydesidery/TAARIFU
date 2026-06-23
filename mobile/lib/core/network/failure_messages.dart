/// Maps a typed [ApiException] to a localised, user-safe message.
///
/// WHY a single mapper: keeps localisation of failures in one place so every
/// screen shows consistent Swahili-first copy (CLAUDE.md §8 — all strings
/// externalised). UI code passes the caught exception and the active
/// [AppLocalizations]; it never hand-writes error strings.
library;

import '../../l10n/app_localizations.dart';
import 'api_exception.dart';

/// Localisation helper for API failures.
class FailureMessages {
  const FailureMessages._();

  /// Returns the localised message for [error] using [l10n].
  ///
  /// For a server domain error ([ApiErrorException]) we prefer the backend's
  /// already-localised [ApiErrorException.serverMessage] (the server resolved it
  /// in the caller's language); for transport failures we use local copy.
  static String of(AppLocalizations l10n, Object error) {
    return switch (error) {
      OfflineException() => l10n.errorOffline,
      TimeoutException() => l10n.errorTimeout,
      ApiErrorException(:final serverMessage, :final statusCode) =>
        serverMessage.isNotEmpty
            ? serverMessage
            : (statusCode >= 500 ? l10n.errorServer : l10n.errorUnknown),
      _ => l10n.errorUnknown,
    };
  }
}
