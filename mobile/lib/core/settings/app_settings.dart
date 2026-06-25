/// The citizen's app-wide preferences: UI language, data-saver mode, and theme.
///
/// WHY these (PRD §14 Swahili-first, §15 data-budget): language is the
/// most-requested control for a Swahili-first audience that may prefer English,
/// and data-saver is a first-class, visible switch because a citizen on a tiny
/// bundle pays real money per megabyte — the app must let them throttle heavy
/// media/sync explicitly (no autoplay, thumbnails first, lite sync on metered
/// links). Theme mode (system/light/dark) lets a citizen pick a dark UI, which
/// is easier on the eyes at night and saves battery on the OLED/low-end phones
/// most of our audience carries. All three are *non-sensitive* (no PII), so they
/// live in a plain settings store, not the Keystore-backed [TokenStore].
library;

import 'package:flutter/material.dart';

/// Immutable snapshot of the citizen's preferences.
class AppSettings {
  /// Creates settings.
  const AppSettings({
    this.languageCode = 'sw',
    this.dataSaver = false,
    this.themeMode = ThemeMode.system,
  });

  /// The active UI language tag — `sw` (default, PRD §14) or `en`.
  final String languageCode;

  /// Whether data-saver is on (throttle heavy media + lite sync on metered
  /// networks). Defaults to off so first-run behaviour is unsurprising, but the
  /// switch is prominent so a low-bundle citizen can opt in immediately.
  final bool dataSaver;

  /// The preferred theme brightness. Defaults to [ThemeMode.system] so the app
  /// honours the device's day/night setting out of the box.
  final ThemeMode themeMode;

  /// The sensible default for a first run: Swahili, data-saver off, follow the
  /// system theme.
  static const AppSettings defaults = AppSettings();

  /// Returns a copy with overrides.
  AppSettings copyWith({
    String? languageCode,
    bool? dataSaver,
    ThemeMode? themeMode,
  }) => AppSettings(
    languageCode: languageCode ?? this.languageCode,
    dataSaver: dataSaver ?? this.dataSaver,
    themeMode: themeMode ?? this.themeMode,
  );

  /// Serialises for the persisted settings file.
  Map<String, dynamic> toJson() => {
    'languageCode': languageCode,
    'dataSaver': dataSaver,
    'themeMode': themeMode.name,
  };

  /// Parses persisted settings, tolerating an absent/garbled file (→ defaults).
  factory AppSettings.fromJson(Map<String, dynamic> json) => AppSettings(
    languageCode: switch (json['languageCode']) {
      'en' => 'en',
      _ => 'sw',
    },
    dataSaver: json['dataSaver'] == true,
    themeMode: switch (json['themeMode']) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    },
  );
}
