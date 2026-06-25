/// The discovery/search screen (Tafuta) — cross-entity search over reps, orgs,
/// announcements, categories, and public reports (PRD discovery; ADR-0017).
///
/// An app-bar search field drives a debounced [SearchCubit]; a horizontal row of
/// kind filter-chips narrows the search; results render as elegant, badged tiles.
/// Every state is first-class: idle (a friendly hint), loading, empty ("no
/// matches"), error/offline (retry), and loaded. Tapping a result routes to the
/// owning surface where the client already has one (an announcement opens its
/// detail); for kinds without a citizen detail screen yet, the tile is informative
/// but inert rather than dead-ending — it never fabricates a destination.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/theme/app_palette.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/search_cubit.dart';
import '../data/search_models.dart';

/// The discovery search view.
class SearchScreen extends StatefulWidget {
  /// Creates the screen. [onOpenResult] is invoked when a tappable result is
  /// selected (the shell routes by [SearchResult.kind]); when it returns `false`
  /// the result has no destination yet and the tile shows as informative-only.
  const SearchScreen({this.onOpenResult, super.key});

  /// Routes a tapped result to its owning surface; returns `true` if it could.
  final bool Function(SearchResult result)? onOpenResult;

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _controller,
          autofocus: true,
          textInputAction: TextInputAction.search,
          decoration: InputDecoration(
            hintText: l10n.searchHint,
            border: InputBorder.none,
            // A clear button so the citizen can reset without selecting-all.
            suffixIcon: ValueListenableBuilder<TextEditingValue>(
              valueListenable: _controller,
              builder: (context, value, _) => value.text.isEmpty
                  ? const SizedBox.shrink()
                  : IconButton(
                      tooltip: l10n.cancelButton,
                      icon: const Icon(Icons.close),
                      onPressed: () {
                        _controller.clear();
                        context.read<SearchCubit>().queryChanged('');
                      },
                    ),
            ),
          ),
          onChanged: (q) => context.read<SearchCubit>().queryChanged(q),
        ),
      ),
      body: Column(
        children: [
          const _KindFilterBar(),
          const Divider(height: 1),
          Expanded(child: _body(context, l10n)),
        ],
      ),
    );
  }

  Widget _body(BuildContext context, AppLocalizations l10n) {
    return BlocBuilder<SearchCubit, SearchState>(
      builder: (context, state) {
        switch (state.status) {
          case SearchStatus.idle:
            return EmptyView(
              message: l10n.searchIdleHint,
              icon: Icons.search_rounded,
            );
          case SearchStatus.loading:
            return LoadingView(label: l10n.loadingLabel);
          case SearchStatus.failure:
            return ErrorRetryView(
              message: FailureMessages.of(l10n, state.error!),
              retryLabel: l10n.retryButton,
              onRetry: () => context.read<SearchCubit>().retry(),
            );
          case SearchStatus.loaded:
            if (state.results.isEmpty) {
              return EmptyView(
                message: l10n.searchNoResults,
                icon: Icons.search_off_rounded,
              );
            }
            return ListView.separated(
              padding: const EdgeInsets.all(AppPalette.spaceMd),
              itemCount: state.results.length,
              separatorBuilder: (_, _) =>
                  const SizedBox(height: AppPalette.spaceSm),
              itemBuilder: (context, i) => _ResultTile(
                result: state.results[i],
                onTap: () => _handleTap(context, l10n, state.results[i]),
              ),
            );
        }
      },
    );
  }

  /// Routes a tapped result, or tells the citizen this kind isn't openable yet.
  void _handleTap(
    BuildContext context,
    AppLocalizations l10n,
    SearchResult result,
  ) {
    final opened = widget.onOpenResult?.call(result) ?? false;
    if (!opened) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(l10n.searchOpenUnavailable)));
    }
  }
}

/// A horizontal, single-select bar of kind filter-chips (All + each kind).
class _KindFilterBar extends StatelessWidget {
  const _KindFilterBar();

  /// The kinds offered as filters (the forward-compatible `unknown` is omitted —
  /// it is never a user-selectable filter).
  static const _kinds = [
    SearchResultKind.representative,
    SearchResultKind.organisation,
    SearchResultKind.announcement,
    SearchResultKind.issueCategory,
    SearchResultKind.publicReport,
  ];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final active = context.select<SearchCubit, SearchResultKind?>(
      (cubit) => cubit.state.kind,
    );
    return SizedBox(
      height: 52,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppPalette.spaceMd),
        children: [
          Padding(
            padding: const EdgeInsets.only(right: AppPalette.spaceSm),
            child: Center(
              child: ChoiceChip(
                label: Text(l10n.searchFilterAll),
                selected: active == null,
                onSelected: (_) => context.read<SearchCubit>().setKind(null),
              ),
            ),
          ),
          for (final kind in _kinds)
            Padding(
              padding: const EdgeInsets.only(right: AppPalette.spaceSm),
              child: Center(
                child: ChoiceChip(
                  label: Text(kind.label(l10n)),
                  selected: active == kind,
                  onSelected: (sel) => context
                      .read<SearchCubit>()
                      .setKind(sel ? kind : null),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// One result tile: a kind-tinted leading icon, the title, an optional snippet,
/// and a kind badge. Const-friendly and cheap on a low-end list.
class _ResultTile extends StatelessWidget {
  const _ResultTile({required this.result, required this.onTap});

  final SearchResult result;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final style = _kindStyle(result.kind, scheme);
    return Card(
      margin: EdgeInsets.zero,
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: style.color.withValues(alpha: 0.14),
          foregroundColor: style.color,
          child: Icon(style.icon, size: 20),
        ),
        title: Text(
          result.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.titleSmall,
        ),
        subtitle: result.snippet == null
            ? Text(result.kind.label(l10n))
            : Text(
                result.snippet!,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
        trailing: _KindBadge(label: result.kind.label(l10n), color: style.color),
        onTap: onTap,
      ),
    );
  }

  ({IconData icon, Color color}) _kindStyle(
    SearchResultKind kind,
    ColorScheme scheme,
  ) => switch (kind) {
    SearchResultKind.representative => (
      icon: Icons.account_balance_rounded,
      color: scheme.primary,
    ),
    SearchResultKind.organisation => (
      icon: Icons.apartment_rounded,
      color: AppPalette.teal,
    ),
    SearchResultKind.announcement => (
      icon: Icons.campaign_rounded,
      color: scheme.primary,
    ),
    SearchResultKind.issueCategory => (
      icon: Icons.category_rounded,
      color: AppPalette.amber,
    ),
    SearchResultKind.publicReport => (
      icon: Icons.report_gmailerrorred_rounded,
      color: AppPalette.teal,
    ),
    SearchResultKind.unknown => (
      icon: Icons.help_outline_rounded,
      color: scheme.onSurfaceVariant,
    ),
  };
}

/// A small, coloured pill marking a result's kind.
class _KindBadge extends StatelessWidget {
  const _KindBadge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.14),
      borderRadius: BorderRadius.circular(AppPalette.radiusChip),
    ),
    child: Text(
      label,
      style: Theme.of(context).textTheme.labelSmall?.copyWith(
        color: color,
        fontWeight: FontWeight.w700,
      ),
    ),
  );
}
