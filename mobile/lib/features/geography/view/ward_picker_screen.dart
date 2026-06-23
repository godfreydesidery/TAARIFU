/// The **manual ward picker** screen (Chagua kata) — the shared UX that replaces
/// hand-typing a ward UUID in the report form, profile locations and find-my-rep
/// (PRD §9.0, §22.6).
///
/// It offers both ways to reach a ward, data-frugally (PRD §15):
///   * a **search box** (type a ward name → `GET /wards?q=`, debounced so a citizen
///     on 2G is not charged a request per keystroke); and
///   * **browse** Region (Mkoa) → District (Wilaya), which lists the district's
///     wards from the cached `GET /districts/{id}/wards` (works offline once opened).
///
/// Picking a ward pops the screen with the chosen [WardSummary]; the caller binds
/// its [WardSummary.id] (so the citizen never types the UUID) while showing the
/// human [WardSummary.name]. Every state — idle, loading, empty, error/offline — is
/// handled explicitly.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/ward_picker_cubit.dart';
import '../data/geography_models.dart';

/// A full-screen ward picker that returns the chosen [WardSummary].
///
/// Push it with its own [WardPickerCubit] (over the shared geography repository)
/// and `await` the result:
/// ```dart
/// final ward = await Navigator.of(context).push<WardSummary>(...);
/// ```
class WardPickerScreen extends StatefulWidget {
  /// Creates the picker screen.
  const WardPickerScreen({super.key});

  @override
  State<WardPickerScreen> createState() => _WardPickerScreenState();
}

class _WardPickerScreenState extends State<WardPickerScreen> {
  final _searchController = TextEditingController();
  Timer? _debounce;

  @override
  void initState() {
    super.initState();
    // Load the regions for the browse path once on open (cached → cheap).
    context.read<WardPickerCubit>().loadRegions();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  /// Debounces the search so a 2G user is not charged a request per keystroke.
  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      if (mounted) {
        context.read<WardPickerCubit>().search(value);
      }
    });
  }

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
                child: Column(
                  children: [
                    TextField(
                      controller: _searchController,
                      onChanged: _onSearchChanged,
                      textInputAction: TextInputAction.search,
                      decoration: InputDecoration(
                        labelText: l10n.wardPickerSearchLabel,
                        hintText: l10n.wardPickerSearchHint,
                        prefixIcon: const Icon(Icons.search),
                        suffixIcon: state.query.isEmpty
                            ? null
                            : IconButton(
                                icon: const Icon(Icons.clear),
                                onPressed: () {
                                  _searchController.clear();
                                  context.read<WardPickerCubit>().search('');
                                },
                              ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    // Browse path: region → district. Hidden while searching to
                    // keep the screen simple and the result list central.
                    if (state.mode == WardListMode.browse ||
                        state.query.isEmpty)
                      _BrowsePickers(state: state),
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(child: _wardList(context, l10n, state)),
            ],
          );
        },
      ),
    );
  }

  Widget _wardList(
    BuildContext context,
    AppLocalizations l10n,
    WardPickerState state,
  ) {
    switch (state.wardStatus) {
      case WardListStatus.idle:
        return EmptyView(message: l10n.wardPickerPrompt, icon: Icons.map_outlined);
      case WardListStatus.loading:
        return LoadingView(label: l10n.loadingLabel);
      case WardListStatus.failure:
        return ErrorRetryView(
          message: FailureMessages.of(l10n, state.error!),
          retryLabel: l10n.retryButton,
          onRetry: () => context.read<WardPickerCubit>().retry(),
        );
      case WardListStatus.loaded:
        if (state.wards.isEmpty) {
          return EmptyView(message: l10n.wardPickerNoResults);
        }
        return ListView.separated(
          itemCount: state.wards.length,
          separatorBuilder: (_, _) => const Divider(height: 1),
          itemBuilder: (context, i) {
            final ward = state.wards[i];
            return ListTile(
              title: Text(ward.name),
              subtitle: ward.locationLabel.isEmpty
                  ? null
                  : Text(ward.locationLabel),
              trailing: const Icon(Icons.chevron_right),
              // Return the chosen ward to the caller (it binds the id, shows name).
              onTap: () => Navigator.of(context).pop(ward),
            );
          },
        );
    }
  }
}

/// The region (Mkoa) and district (Wilaya) dropdowns of the browse path.
class _BrowsePickers extends StatelessWidget {
  const _BrowsePickers({required this.state});

  final WardPickerState state;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      children: [
        DropdownButtonFormField<String>(
          initialValue: state.selectedRegionId,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l10n.myRepsRegionLabel,
            suffixIcon: state.regionsLoading
                ? const _MiniSpinner()
                : null,
          ),
          items: [
            for (final r in state.regions)
              DropdownMenuItem(value: r.id, child: Text(r.name)),
          ],
          onChanged: state.regionsLoading
              ? null
              : (v) {
                  if (v != null) {
                    context.read<WardPickerCubit>().selectRegion(v);
                  }
                },
        ),
        const SizedBox(height: 8),
        DropdownButtonFormField<String>(
          initialValue: state.selectedDistrictId,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l10n.wardPickerDistrictLabel,
            suffixIcon: state.districtsLoading
                ? const _MiniSpinner()
                : null,
          ),
          items: [
            for (final d in state.districts)
              DropdownMenuItem(value: d.id, child: Text(d.name)),
          ],
          onChanged: (state.districts.isEmpty || state.districtsLoading)
              ? null
              : (v) {
                  if (v != null) {
                    context.read<WardPickerCubit>().selectDistrict(v);
                  }
                },
        ),
      ],
    );
  }
}

/// A small inline progress indicator for a dropdown's suffix.
class _MiniSpinner extends StatelessWidget {
  const _MiniSpinner();

  @override
  Widget build(BuildContext context) => const Padding(
    padding: EdgeInsets.all(12),
    child: SizedBox(
      height: 16,
      width: 16,
      child: CircularProgressIndicator(strokeWidth: 2),
    ),
  );
}
