/// The app's Material theme.
///
/// WHY high-contrast + generous touch targets: many Taarifu citizens use cheap,
/// low-end or cracked screens in bright sunlight and may have low literacy
/// (PRD §14); large tap areas and strong contrast are accessibility essentials,
/// not polish.
library;

import 'package:flutter/material.dart';

/// Builds the shared light theme.
class AppTheme {
  const AppTheme._();

  /// A seed colour in Tanzania's civic green family.
  static const Color _seed = Color(0xFF0B6E4F);

  /// The app's light theme with large, high-contrast components.
  static ThemeData light() {
    final scheme = ColorScheme.fromSeed(seedColor: _seed);
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      // Generous minimum tap target for low-end touchscreens.
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 18),
      ),
    );
  }
}
