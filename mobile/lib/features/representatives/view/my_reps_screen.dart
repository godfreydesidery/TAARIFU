/// The "find my representatives" screen (Wawakilishi Wangu).
///
/// Demonstrates two real public endpoints end-to-end:
///   * `GET /regions` — loaded by [_RegionPicker] to prove the geography read +
///     offline cache (the citizen orients by region/Mkoa first).
///   * `GET /representatives/by-ward/{wardId}` — loaded by [MyRepsCubit] once a
///     ward id is supplied, returning the MP (Mbunge), Councillor (Diwani), and
///     ward executive bundle.
///
/// The citizen reaches a ward through the shared [WardPickerField] (search/browse,
/// no hand-typed UUID); the screen then proves the by-ward contract and all UI
/// states, degrading gracefully when a slot has no representative (PRD §22.6).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/di/app_dependencies.dart';
import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../features/geography/data/geography_models.dart';
import '../../../features/geography/view/ward_picker_field.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/my_reps_cubit.dart';
import '../data/representative_models.dart';

/// The find-my-representatives tab.
class MyRepsScreen extends StatefulWidget {
  /// Creates the screen over the app [dependencies] (for the ward picker).
  const MyRepsScreen({required this.dependencies, super.key});

  /// The composition root, supplied to the ward picker.
  final AppDependencies dependencies;

  @override
  State<MyRepsScreen> createState() => _MyRepsScreenState();
}

class _MyRepsScreenState extends State<MyRepsScreen> {
  WardSummary? _ward;

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
            // Ward picker (replaces the old hand-typed UUID): choose a Kata, then
            // load its representatives.
            WardPickerField(
              dependencies: widget.dependencies,
              selected: _ward,
              onSelected: (w) {
                setState(() => _ward = w);
                context.read<MyRepsCubit>().loadForWard(w.id);
              },
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
            final ward = _ward;
            if (ward != null) {
              context.read<MyRepsCubit>().loadForWard(ward.id);
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
