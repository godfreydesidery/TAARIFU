/// The **manual ward picker** (Chagua kata) — the GPS-free way a citizen pins a
/// ward (Kata). Opened as a route by the report form, profile locations, and
/// find-my-rep; pops the chosen [WardSummary] back to the caller, replacing the
/// hand-typed ward UUID those flows used to require.
///
/// Two modes, toggled by a segmented control:
///   * **Search** (Tafuta) — type a ward name → `GET /wards?q=` (fast path);
///   * **Browse** (Vinjari) — Region → District → ward list (orient by
///     geography), backed by the cached district listing so it works offline.
///
/// Every state is handled (loading/empty/error/offline) so the picker never
/// hard-fails on a weak network (PRD §15, §22.6).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/ward_picker_cubit.dart';
import '../data/geography_models.dart';

/// The manual ward-picker screen. Returns the chosen [WardSummary] via
/// `Navigator.pop` (or `null` if dismissed).
class WardPickerScreen extends StatefulWidget {
  /// Creates the screen.
  const WardPickerScreen({super.key});

  @override
  State<WardPickerScreen> createState() => _WardPickerScreenState();
}

class _WardPickerScreenState extends State<WardPickerScreen> {
  final _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    // Default to browse: load regions so a citizen who doesn't know the ward
    // name can still drill down.
    context.read<WardPickerCubit>().loadRegions();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  void _choose(WardSummary ward) => Navigator.of(context).pop(ward);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.wardPickerTitle)),
      body: BlocBuilder<WardPickerCubit, WardPickerState>(
        builder: (context, state) {
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.all(12),
                child: SegmentedButton<WardPickerMode>(
                  segments: [
                    ButtonSegment(
                      value: WardPickerMode.search,
                      icon: const Icon(Icons.search),
                      label: Text(l10n.wardPickerSearchTab),
                    ),
                    ButtonSegment(
                      value: WardPickerMode.browse,
                      icon: const Icon(Icons.account_tree_outlined),
                      label: Text(l10n.wardPickerBrowseTab),
                    ),
                  ],
                  selected: {state.mode},
                  onSelectionChanged: (s) =>
                      context.read<WardPickerCubit>().setMode(s.first),
                ),
              ),
              Expanded(
                child: state.mode == WardPickerMode.search
                    ? _searchBody(context, l10n, state)
                    : _browseBody(context, l10n, state),
              ),
            ],
          );
        },
      ),
    );
  }

  // --- Search mode ---------------------------------------------------------

  Widget _searchBody(
    BuildContext context,
    AppLocalizations l10n,
    WardPickerState state,
  ) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: TextField(
            controller: _searchController,
            autofocus: true,
            decoration: InputDecoration(
              hintText: l10n.wardPickerSearchHint,
              prefixIcon: const Icon(Icons.search),
              suffixIcon: state.searching
                  ? const Padding(
                      padding: EdgeInsets.all(12),
                      child: SizedBox(
                        height: 16,
                        width: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    )
                  : null,
            ),
            onChanged: (q) => context.read<WardPickerCubit>().search(q),
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: _searchController.text.trim().isEmpty
              ? const SizedBox.shrink()
              : state.results.isEmpty && !state.searching
              ? EmptyView(message: l10n.wardPickerNoResults)
              : _wardList(state.results),
        ),
      ],
    );
  }

  // --- Browse mode ---------------------------------------------------------

  Widget _browseBody(
    BuildContext context,
    AppLocalizations l10n,
    WardPickerState state,
  ) {
    if (state.status == WardPickerStatus.failure && state.regions.isEmpty) {
      return ErrorRetryView(
        message: FailureMessages.of(l10n, state.error!),
        retryLabel: l10n.retryButton,
        onRetry: () => context.read<WardPickerCubit>().loadRegions(),
      );
    }
    // Show the deepest reached level: wards > districts > regions.
    if (state.selectedDistrict != null) {
      return _levelView(
        context: context,
        l10n: l10n,
        state: state,
        title: l10n.wardPickerPickWard,
        child: state.status == WardPickerStatus.loading
            ? LoadingView(label: l10n.loadingLabel)
            : state.wards.isEmpty
            ? EmptyView(message: l10n.wardPickerNoWards)
            : _wardList(state.wards),
      );
    }
    if (state.selectedRegion != null) {
      return _levelView(
        context: context,
        l10n: l10n,
        state: state,
        title: l10n.wardPickerPickDistrict,
        child: state.status == WardPickerStatus.loading
            ? LoadingView(label: l10n.loadingLabel)
            : ListView(
                children: [
                  for (final d in state.districts)
                    ListTile(
                      title: Text(d.name),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () =>
                          context.read<WardPickerCubit>().selectDistrict(d),
                    ),
                ],
              ),
      );
    }
    if (state.status == WardPickerStatus.loading && state.regions.isEmpty) {
      return LoadingView(label: l10n.loadingLabel);
    }
    return _levelView(
      context: context,
      l10n: l10n,
      state: state,
      title: l10n.wardPickerPickRegion,
      child: ListView(
        children: [
          for (final r in state.regions)
            ListTile(
              title: Text(r.name),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.read<WardPickerCubit>().selectRegion(r),
            ),
        ],
      ),
    );
  }

  /// A drill-down level with a breadcrumb header showing the chosen ancestors.
  Widget _levelView({
    required BuildContext context,
    required AppLocalizations l10n,
    required WardPickerState state,
    required String title,
    required Widget child,
  }) {
    final crumbs = <String>[
      if (state.selectedRegion != null) state.selectedRegion!.name,
      if (state.selectedDistrict != null) state.selectedDistrict!.name,
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (crumbs.isNotEmpty)
                Text(
                  crumbs.join(' › '),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              Text(title, style: Theme.of(context).textTheme.titleMedium),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(child: child),
      ],
    );
  }

  /// The shared ward pick-list used by both modes: tapping a row returns it.
  Widget _wardList(List<WardSummary> wards) => ListView.separated(
    itemCount: wards.length,
    separatorBuilder: (_, _) => const Divider(height: 1),
    itemBuilder: (context, i) {
      final w = wards[i];
      return ListTile(
        title: Text(w.name),
        // Council · district disambiguates same-named wards in one line.
        subtitle: [
          w.councilName,
          w.districtName,
        ].any((s) => s != null && s.isNotEmpty)
            ? Text(
                [
                  if (w.councilName != null && w.councilName!.isNotEmpty)
                    w.councilName!,
                  if (w.districtName != null && w.districtName!.isNotEmpty)
                    w.districtName!,
                ].join(' · '),
              )
            : null,
        trailing: const Icon(Icons.check_circle_outline),
        onTap: () => _choose(w),
      );
    },
  );
}
