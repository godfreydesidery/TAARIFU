/// The citizen's app-wide preferences: UI language and data-saver mode.
///
/// WHY these two now (PRD §14 Swahili-first, §15 data-budget): language is the
/// most-requested control for a Swahili-first audience that may prefer English,
/// and data-saver is a first-class, visible switch because a citizen on a tiny
/// bundle pays real money per megabyte — the app must let them throttle heavy
/// media/sync explicitly (no autoplay, thumbnails first, lite sync on metered
/// links). Both are *non-sensitive* (no PII), so they live in a plain settings
/// store, not the Keystore-backed [TokenStore].
library;

/// Immutable snapshot of the citizen's preferences.
class AppSettings {
  /// Creates settings.
  const AppSettings({
    this.languageCode = 'sw',
    this.dataSaver = false,
  });

  /// The active UI language tag — `sw` (default, PRD §14) or `en`.
  final String languageCode;

  /// Whether data-saver is on (throttle heavy media + lite sync on metered
  /// networks). Defaults to off so first-run behaviour is unsurprising, but the
  /// switch is prominent so a low-bundle citizen can opt in immediately.
  final bool dataSaver;

  /// The sensible default for a first run: Swahili, data-saver off.
  static const AppSettings defaults = AppSettings();

  /// Returns a copy with overrides.
  AppSettings copyWith({String? languageCode, bool? dataSaver}) => AppSettings(
    languageCode: languageCode ?? this.languageCode,
    dataSaver: dataSaver ?? this.dataSaver,
  );

  /// Serialises for the persisted settings file.
  Map<String, dynamic> toJson() => {
    'languageCode': languageCode,
    'dataSaver': dataSaver,
  };

  /// Parses persisted settings, tolerating an absent/garbled file (→ defaults).
  factory AppSettings.fromJson(Map<String, dynamic> json) => AppSettings(
    languageCode: switch (json['languageCode']) {
      'en' => 'en',
      _ => 'sw',
    },
    dataSaver: json['dataSaver'] == true,
  );
}
