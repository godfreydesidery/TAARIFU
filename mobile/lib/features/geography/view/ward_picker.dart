/// One entry point for opening the **manual ward picker** so every flow that
/// needs a ward (report form, profile locations, find-my-rep) wires the cubit
/// the same way (DRY, CLAUDE.md §3) instead of repeating the `BlocProvider`.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../bloc/ward_picker_cubit.dart';
import '../data/geography_models.dart';
import '../data/geography_repository.dart';
import 'ward_picker_screen.dart';

/// Static helper to present the ward picker.
abstract final class WardPicker {
  /// Pushes the [WardPickerScreen] over a freshly-scoped [WardPickerCubit] and
  /// resolves to the chosen [WardSummary], or `null` if the citizen backed out.
  ///
  /// The cubit is scoped to this route (cheap, fresh per visit) and built from
  /// the shared [geographyRepository] passed in by the caller's composition root.
  static Future<WardSummary?> open(
    BuildContext context, {
    required GeographyRepository geographyRepository,
  }) {
    return Navigator.of(context).push<WardSummary>(
      MaterialPageRoute<WardSummary>(
        builder: (_) => BlocProvider(
          create: (_) => WardPickerCubit(repository: geographyRepository),
          child: const WardPickerScreen(),
        ),
      ),
    );
  }
}
