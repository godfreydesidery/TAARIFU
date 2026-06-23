/// Application-wide runtime configuration, resolved from compile-time
/// `--dart-define` values with safe defaults.
///
/// WHY this exists: the legacy `taarifu-mob-app` hardcoded its base URL, which
/// made it impossible to point one build at dev / staging / prod without editing
/// source (CLAUDE.md §8 — no hardcoded URLs). [AppConfig] centralises that so the
/// base URL is a build-time input, never a literal scattered through the code.
library;

/// Immutable holder for environment configuration.
///
/// All values come from `--dart-define` at build time (see `mobile/README.md`),
/// falling back to developer-friendly defaults so a plain `flutter run` works
/// against a locally running backend.
class AppConfig {
  /// Creates a config with an explicit [apiBaseUrl].
  const AppConfig({required this.apiBaseUrl});

  /// The base URL of the Taarifu backend, including the `/api/v1` context path.
  ///
  /// Default `http://10.0.2.2:8081/api/v1`: on the Android emulator `10.0.2.2`
  /// is an alias for the host machine's `localhost`, and the backend serves the
  /// versioned API under context-path `/api/v1` on port `8081`
  /// (backend `application.yml`). Override per environment with
  /// `--dart-define=TAARIFU_API_BASE_URL=https://api.example.tz/api/v1`.
  final String apiBaseUrl;

  /// Builds the config from the ambient `--dart-define` environment.
  ///
  /// Reads `TAARIFU_API_BASE_URL`; when unset, uses the emulator-to-host default.
  factory AppConfig.fromEnvironment() {
    const baseUrl = String.fromEnvironment(
      'TAARIFU_API_BASE_URL',
      defaultValue: 'http://10.0.2.2:8081/api/v1',
    );
    return const AppConfig(apiBaseUrl: baseUrl);
  }
}
