/// The "find my representatives" screen (Wawakilishi Wangu).
///
/// Demonstrates two real public endpoints end-to-end:
///   * `GET /regions` — loaded by [_RegionPicker] to prove the geography read +
///     offline cache (the citizen orients by region/Mkoa first).
///   * `GET /representatives/by-ward/{wardId}` — loaded by [MyRepsCubit] once a
///     ward id is supplied, returning the MP (Mbunge), Councillor (Diwani), and
///     ward executive bundle.
///
/// The citizen picks their ward through the **manual ward picker** (replacing
/// the old hand-typed ward UUID) — `GET /districts/{id}/wards` + `GET /wards?q=`
/// behind [WardPicker]; in production this is complemented by GPS
/// `/locations/resolve`. The screen proves the by-ward contract and all UI
/// states (loading/empty/error/offline, degrading gracefully per PRD §22.6).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../features/geography/data/geography_models.dart';
import '../../../features/geography/data/geography_repository.dart';
import '../../../features/geography/view/ward_picker.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/my_reps_cubit.dart';
import '../data/representative_models.dart';

/// The find-my-representatives tab.
class MyRepsScreen extends StatefulWidget {
  /// Creates the screen over the shared [geographyRepository] (for the ward
  /// picker).
  const MyRepsScreen({required this.geographyRepository, super.key});

  /// Civic-geography reads backing the manual ward picker.
  final GeographyRepository geographyRepository;

  @override
  State<MyRepsScreen> createState() => _MyRepsScreenState();
}

class _MyRepsScreenState extends State<MyRepsScreen> {
  WardSummary? _ward;

  /// Opens the picker; on a pick, immediately resolves that ward's reps.
  Future<void> _pickWard() async {
    final chosen = await WardPicker.open(
      context,
      geographyRepository: widget.geographyRepository,
    );
    if (chosen != null && mounted) {
      setState(() => _ward = chosen);
      context.read<MyRepsCubit>().loadForWard(chosen.id);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return BlocBuilder<MyRepsCubit, MyRepsState>(
      builder: (context, state) {
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              l10n.myRepsPickWard,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            // Ward chosen via the manual ward picker (no hand-typed UUID).
            Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                leading: const Icon(Icons.location_city_outlined),
                title: Text(
                  _ward == null
                      ? l10n.wardPickerChooseButton
                      : l10n.wardPickerChosenLabel(_ward!.qualifiedLabel),
                ),
                trailing: TextButton(
                  onPressed: _pickWard,
                  child: Text(
                    _ward == null
                        ? l10n.wardPickerChooseButton
                        : l10n.wardPickerChangeButton,
                  ),
                ),
                onTap: _pickWard,
              ),
            ),
            const SizedBox(height: 24),
            _buildResult(context, l10n, state),
          ],
        );
      },
    );
  }

  Widget _buildResult(
    BuildContext context,
    AppLocalizations l10n,
    MyRepsState state,
  ) {
    switch (state.status) {
      case MyRepsStatus.idle:
        return const SizedBox.shrink();
      case MyRepsStatus.loading:
        return LoadingView(label: l10n.loadingLabel);
      case MyRepsStatus.failure:
        return ErrorRetryView(
          message: FailureMessages.of(l10n, state.error!),
          retryLabel: l10n.retryButton,
          onRetry: () {
            final id = _ward?.id;
            if (id != null) {
              context.read<MyRepsCubit>().loadForWard(id);
            }
          },
        );
      case MyRepsStatus.loaded:
        return _RepsBundle(data: state.data!);
    }
  }
}

/// Renders the MP / Councillor / Ward-executive slots, each degrading to a
/// "none found" line rather than hard-failing (PRD §22.6).
class _RepsBundle extends StatelessWidget {
  const _RepsBundle({required this.data});

  final MyRepresentatives data;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final councillor = data.councillors.isNotEmpty
        ? data.councillors.first
        : null;
    final executive = data.wardExecutives.isNotEmpty
        ? data.wardExecutives.first
        : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          data.wardName,
          style: Theme.of(context).textTheme.titleLarge,
        ),
        if (data.constituencyName != null)
          Text(data.constituencyName!),
        const SizedBox(height: 12),
        _RepSlot(label: l10n.myRepsMpLabel, rep: data.mp),
        _RepSlot(label: l10n.myRepsCouncillorLabel, rep: councillor),
        _RepSlot(label: l10n.myRepsExecutiveLabel, rep: executive),
      ],
    );
  }
}

/// A single labelled representative slot.
class _RepSlot extends StatelessWidget {
  const _RepSlot({required this.label, required this.rep});

  final String label;
  final RepresentativeSummary? rep;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = rep;
    return Card(
      child: ListTile(
        title: Text(label),
        subtitle: Text(
          r == null
              ? l10n.myRepsNone
              : [
                  if (r.partyName != null) r.partyName!,
                  if (r.constituencyName != null) r.constituencyName!,
                  if (!r.isSitting) r.status,
                ].join(' · '),
        ),
        trailing: r == null ? null : const Icon(Icons.chevron_right),
      ),
    );
  }
}
