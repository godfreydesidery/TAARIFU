/// The Taarifu brand palette and design tokens.
///
/// WHY a hand-tuned palette (not a bare `fromSeed`): Taarifu is a civic, public
/// product that must feel trustworthy and distinctly Tanzanian — a confident
/// civic green anchored by a warm "jua" (sun) amber accent — while staying
/// legible on cheap, low-end or cracked screens in bright sunlight (PRD §14).
/// Colours here are chosen for AA contrast on both light and dark surfaces, not
/// just for looks. These are pure constants (no Flutter widgets) so the theme,
/// feed cards, and chips share one source of truth (DRY, CLAUDE.md §3).
library;

import 'package:flutter/material.dart';

/// Brand colours + spacing/elevation tokens shared across the design system.
class AppPalette {
  const AppPalette._();

  // --- Brand colours -------------------------------------------------------

  /// Primary civic green — Taarifu's anchor colour (trust, growth, the flag's
  /// green). Used for the seed and primary actions.
  static const Color green = Color(0xFF0B6E4F);

  /// A deeper green for gradients and dark-surface accents.
  static const Color greenDeep = Color(0xFF064E38);

  /// A brighter green used in the dark theme so primary actions stay vivid on
  /// dark surfaces (a too-dark green disappears at night).
  static const Color greenBright = Color(0xFF2E9E78);

  /// Warm "jua" amber — the secondary/accent for highlights, reactions, and the
  /// report call-to-action. Borrowed from the Tanzanian sun, it draws the eye to
  /// the single most important civic action without shouting.
  static const Color amber = Color(0xFFF4A828);

  /// A teal tertiary used sparingly for area/locality chips so geography reads
  /// distinctly from category accents.
  static const Color teal = Color(0xFF1C7C8C);

  /// Error/destructive red, AA-legible on both themes.
  static const Color danger = Color(0xFFC0392B);

  // --- Spacing scale (4-pt grid) ------------------------------------------

  /// Extra-small gap (4dp).
  static const double spaceXs = 4;

  /// Small gap (8dp).
  static const double spaceSm = 8;

  /// Medium gap (12dp) — the default inter-card gap.
  static const double spaceMd = 12;

  /// Large gap (16dp) — the default screen padding.
  static const double spaceLg = 16;

  /// Extra-large gap (24dp) — section breathing room.
  static const double spaceXl = 24;

  // --- Corner radii (rounded, elegant) ------------------------------------

  /// Card corner radius — generous rounding for a soft, modern feel.
  static const double radiusCard = 20;

  /// Chip / pill corner radius.
  static const double radiusChip = 30;

  /// Input / button corner radius.
  static const double radiusInput = 14;

  /// Bottom-sheet top corner radius.
  static const double radiusSheet = 28;
}
