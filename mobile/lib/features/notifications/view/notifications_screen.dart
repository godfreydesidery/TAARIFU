/// The notifications inbox (Arifa) — PRD §13, UC-G09, US-5.1.
///
/// Lists the citizen's notifications newest-first with a mark-read action, and an
/// app-bar action to open the preferences screen. A note surfaces the platform's
/// SMS fallback (the backend sends an SMS when there is no live push token —
/// US-5.1), so a low-connectivity citizen understands they will still be reached.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/notifications_cubit.dart';
import '../data/notification_models.dart';

/// The notification inbox.
class NotificationsScreen extends StatelessWidget {
  /// Creates the screen. [onOpenPrefs] opens the preferences screen.
  const NotificationsScreen({required this.onOpenPrefs, super.key});

  /// Opens the notification-preferences screen.
  final VoidCallback onOpenPrefs;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.notificationsTitle),
        actions: [
          IconButton(
            tooltip: l10n.notificationsPrefsButton,
            icon: const Icon(Icons.tune),
            onPressed: onOpenPrefs,
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
            child: Row(
              children: [
                const Icon(Icons.sms_outlined, size: 18),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    l10n.notificationsSmsFallbackNote,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ],
            ),
          ),
          Expanded(child: _list(context, l10n)),
        ],
      ),
    );
  }

  Widget _list(BuildContext context, AppLocalizations l10n) {
    return BlocBuilder<NotificationsCubit, NotificationsState>(
      builder: (context, state) {
        switch (state.status) {
          case NotificationsStatus.initial:
          case NotificationsStatus.loading:
            return LoadingView(label: l10n.loadingLabel);
          case NotificationsStatus.failure:
            return ErrorRetryView(
              message: FailureMessages.of(l10n, state.error!),
              retryLabel: l10n.retryButton,
              onRetry: () => context.read<NotificationsCubit>().load(),
            );
          case NotificationsStatus.loaded:
            if (state.items.isEmpty) {
              return EmptyView(
                message: l10n.notificationsEmpty,
                icon: Icons.notifications_none,
              );
            }
            return RefreshIndicator(
              onRefresh: () => context.read<NotificationsCubit>().load(),
              child: ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: state.items.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (context, i) =>
                    _NotificationTile(notification: state.items[i]),
              ),
            );
        }
      },
    );
  }
}

class _NotificationTile extends StatelessWidget {
  const _NotificationTile({required this.notification});

  final AppNotification notification;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final read = notification.isRead;
    return Card(
      child: ListTile(
        leading: Icon(
          read ? Icons.notifications_none : Icons.notifications_active,
          color: read ? null : Theme.of(context).colorScheme.primary,
        ),
        title: Text(notification.type),
        subtitle: Text('${notification.channel} · ${notification.status}'),
        trailing: read
            ? null
            : TextButton(
                onPressed: () =>
                    context.read<NotificationsCubit>().markRead(notification.id),
                child: Text(l10n.notificationsMarkRead),
              ),
      ),
    );
  }
}
