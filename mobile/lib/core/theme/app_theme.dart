/// The Taarifu Material 3 design system: a refined light + dark theme over the
/// brand [AppPalette].
///
/// WHY high-contrast + generous touch targets + a hand-tuned palette: many
/// Taarifu citizens use cheap, low-end or cracked screens in bright sunlight and
/// may have low literacy (PRD §14); large tap areas, strong contrast, and a
/// clear, consistent visual language are accessibility essentials, not polish.
/// Dark mode is offered because an OLED-friendly dark UI saves battery and is
/// easier on the eyes at night — and because citizens expect a modern, elegant
/// app. The theme is centralised here so every screen inherits the same rounded
/// cards, spacing, and typography (DRY, CLAUDE.md §3) — feature widgets read
/// `Theme.of(context)` and never hardcode colours.
library;

import 'package:flutter/material.dart';

import 'app_palette.dart';

/// Builds the shared light and dark themes.
class AppTheme {
  const AppTheme._();

  /// The app's light theme.
  static ThemeData light() => _build(Brightness.light);

  /// The app's dark theme (OLED-friendly, battery-saving for low-end phones).
  static ThemeData dark() => _build(Brightness.dark);

  /// Builds a theme for the given [brightness] from one shared recipe so light
  /// and dark stay visually consistent (only the surfaces and seed brightness
  /// differ).
  static ThemeData _build(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    final scheme =
        ColorScheme.fromSeed(
          seedColor: AppPalette.green,
          brightness: brightness,
          primary: isDark ? AppPalette.greenBright : AppPalette.green,
          secondary: AppPalette.amber,
          tertiary: AppPalette.teal,
          error: AppPalette.danger,
        ).copyWith(
          // Slightly off-white / near-black surfaces read softer than pure
          // #FFFFFF/#000000 and reduce glare on cheap LCDs in sunlight.
          surface: isDark ? const Color(0xFF121414) : const Color(0xFFF7F9F8),
        );

    final textTheme = _textTheme(scheme);

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
      textTheme: textTheme,
      // A flat, integrated app bar that blends with the scrolling content for a
      // clean, modern read (no heavy Material-2 shadow line).
      appBarTheme: AppBarTheme(
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
        elevation: 0,
        scrolledUnderElevation: 2,
        centerTitle: false,
        titleTextStyle: textTheme.titleLarge,
      ),
      // Elegant, generously rounded cards with a hairline outline instead of a
      // heavy drop shadow — lighter to render on low-end GPUs and cleaner.
      cardTheme: CardThemeData(
        elevation: 0,
        margin: EdgeInsets.zero,
        clipBehavior: Clip.antiAlias,
        color: scheme.surfaceContainerLow,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusCard),
          side: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.5)),
        ),
      ),
      // Generous minimum tap target for low-end touchscreens (PRD §14).
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(52),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppPalette.radiusInput),
          ),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(48),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppPalette.radiusInput),
          ),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
      ),
      // Filled, rounded inputs — softer and more touch-friendly than the boxy
      // Material-2 outline, with a clear focus colour for visibility.
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest.withValues(alpha: 0.4),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 18),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusInput),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusInput),
          borderSide: BorderSide(color: scheme.outlineVariant),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusInput),
          borderSide: BorderSide(color: scheme.primary, width: 2),
        ),
      ),
      chipTheme: ChipThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusChip),
        ),
        side: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.6)),
        labelStyle: textTheme.labelLarge,
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      ),
      // An elevated, pill FAB for the primary "report" action — the amber accent
      // makes it the unmistakable focal point of the app.
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: AppPalette.amber,
        foregroundColor: Colors.black87,
        elevation: 2,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusChip),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        height: 68,
        backgroundColor: scheme.surface,
        elevation: 3,
        indicatorColor: scheme.primary.withValues(alpha: 0.16),
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => textTheme.labelMedium?.copyWith(
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w700
                : FontWeight.w500,
          ),
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: scheme.surface,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(AppPalette.radiusSheet),
          ),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: scheme.outlineVariant.withValues(alpha: 0.5),
        space: AppPalette.spaceXl,
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppPalette.radiusInput),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      ),
    );
  }

  /// A typography scale tuned for readability: slightly heavier headlines for a
  /// confident civic voice and comfortable body sizes for low-literacy readers
  /// (larger than Material's default body so Swahili copy is easy to scan).
  static TextTheme _textTheme(ColorScheme scheme) {
    final base = ThemeData(brightness: scheme.brightness).textTheme;
    return base.copyWith(
      headlineSmall: base.headlineSmall?.copyWith(
        fontWeight: FontWeight.w700,
        letterSpacing: -0.5,
      ),
      titleLarge: base.titleLarge?.copyWith(
        fontWeight: FontWeight.w700,
        letterSpacing: -0.2,
      ),
      titleMedium: base.titleMedium?.copyWith(fontWeight: FontWeight.w600),
      titleSmall: base.titleSmall?.copyWith(fontWeight: FontWeight.w600),
      bodyLarge: base.bodyLarge?.copyWith(height: 1.45),
      bodyMedium: base.bodyMedium?.copyWith(height: 1.4),
      labelLarge: base.labelLarge?.copyWith(fontWeight: FontWeight.w600),
    );
  }
}
