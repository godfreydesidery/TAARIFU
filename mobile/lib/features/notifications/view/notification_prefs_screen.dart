/// The notification-preferences screen (Mapendeleo ya arifa) — PRD §13, UC-G08.
///
/// Lists the citizen's `(type, channel)` preferences with a per-row toggle. An
/// empty list is a valid state (defaults apply server-side) — the screen says so
/// rather than showing a blank. Always-on types rejected by the backend surface
/// as a localised error and the toggle reverts.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/notification_prefs_cubit.dart';
import '../data/notification_models.dart';

/// The preferences screen.
class NotificationPrefsScreen extends StatelessWidget {
  /// Creates the screen.
  const NotificationPrefsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.notificationsPrefsTitle)),
      body: BlocConsumer<NotificationPrefsCubit, NotificationPrefsState>(
        listenWhen: (p, c) => c.error != null && p.error != c.error,
        listener: (context, state) {
          ScaffoldMessenger.of(context)
            ..hideCurrentSnackBar()
            ..showSnackBar(
              SnackBar(content: Text(FailureMessages.of(l10n, state.error!))),
            );
          // Reload to revert an optimistic toggle the server rejected.
          context.read<NotificationPrefsCubit>().load();
        },
        builder: (context, state) {
          switch (state.status) {
            case NotificationPrefsStatus.initial:
            case NotificationPrefsStatus.loading:
              return LoadingView(label: l10n.loadingLabel);
            case NotificationPrefsStatus.failure:
              return ErrorRetryView(
                message: FailureMessages.of(l10n, state.error!),
                retryLabel: l10n.retryButton,
                onRetry: () => context.read<NotificationPrefsCubit>().load(),
              );
            case NotificationPrefsStatus.loaded:
              if (state.prefs.isEmpty) {
                return EmptyView(
                  message: l10n.prefsEmpty,
                  icon: Icons.tune,
                );
              }
              return ListView.builder(
                padding: const EdgeInsets.all(8),
                itemCount: state.prefs.length,
                itemBuilder: (context, i) => _PrefTile(
                  pref: state.prefs[i],
                  saving: state.savingId == state.prefs[i].id,
                ),
              );
          }
        },
      ),
    );
  }
}

class _PrefTile extends StatelessWidget {
  const _PrefTile({required this.pref, required this.saving});

  final NotificationPreference pref;
  final bool saving;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SwitchListTile(
      title: Text(pref.type),
      subtitle: Text(l10n.prefChannelLabel(pref.channel)),
      value: pref.enabled,
      onChanged: saving
          ? null
          : (v) => context.read<NotificationPrefsCubit>().toggle(pref, v),
    );
  }
}
