/// A reusable form field that lets a citizen **pick a ward (Kata)** instead of
/// typing its UUID — the shared building block for the report form, profile
/// locations and find-my-representatives (PRD §9.0, §22.6).
///
/// WHY one shared widget (DRY, CLAUDE.md §3): all three surfaces previously asked
/// the citizen to hand-type a ward UUID — unusable on a feature phone and a known
/// sin to remove. Centralising the "tap to choose a ward" affordance here means the
/// picker UX, the `WardPickerCubit` wiring and the empty/selected presentation live
/// in exactly one place; each caller just receives the chosen [WardSummary].
///
/// It shows the selected ward's human [WardSummary.name] (+ its council·district
/// breadcrumb) to the citizen, while the caller binds the machine [WardSummary.id].
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/di/app_dependencies.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/ward_picker_cubit.dart';
import '../data/geography_models.dart';
import 'ward_picker_screen.dart';

/// A tappable field showing the chosen ward (or a prompt), opening the picker.
class WardPickerField extends StatelessWidget {
  /// Creates the field.
  ///
  /// [dependencies] supplies the shared geography repository the picker uses.
  /// [selected] is the currently chosen ward (or `null`). [onSelected] is called
  /// with the ward the citizen picks. [enabled] disables the tap while a parent
  /// form is busy. [labelText] overrides the default "ward" label.
  const WardPickerField({
    required this.dependencies,
    required this.selected,
    required this.onSelected,
    this.enabled = true,
    this.labelText,
    super.key,
  });

  /// The composition root (for the geography repository).
  final AppDependencies dependencies;

  /// The currently selected ward, or `null` if none chosen yet.
  final WardSummary? selected;

  /// Called with the ward the citizen chooses.
  final ValueChanged<WardSummary> onSelected;

  /// Whether the field accepts taps.
  final bool enabled;

  /// Optional label override.
  final String? labelText;

  /// Opens the picker (with its own cubit over the shared repository) and reports
  /// the chosen ward back to the caller.
  Future<void> _open(BuildContext context) async {
    final ward = await Navigator.of(context).push<WardSummary>(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => WardPickerCubit(
            repository: dependencies.geographyRepository,
          ),
          child: const WardPickerScreen(),
        ),
      ),
    );
    if (ward != null) {
      onSelected(ward);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final chosen = selected;
    return InkWell(
      onTap: enabled ? () => _open(context) : null,
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: labelText ?? l10n.wardFieldLabel,
          prefixIcon: const Icon(Icons.location_on_outlined),
          suffixIcon: const Icon(Icons.chevron_right),
          enabled: enabled,
        ),
        child: Text(
          chosen == null
              ? l10n.wardFieldEmpty
              : (chosen.locationLabel.isEmpty
                    ? chosen.name
                    : '${chosen.name} · ${chosen.locationLabel}'),
          style: chosen == null
              ? TextStyle(color: Theme.of(context).hintColor)
              : null,
        ),
      ),
    );
  }
}
