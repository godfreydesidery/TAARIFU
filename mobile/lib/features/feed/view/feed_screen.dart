/// The home/feed screen: the citizen's personalised announcements.
///
/// Consumes `GET /feed` via [FeedCubit]. The feed needs a session (T1); for a
/// Guest it shows a sign-in prompt instead of a failing call. Handles all four
/// states: loading, loaded, empty, and error/offline.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/bloc/auth_bloc.dart';
import '../../auth/bloc/auth_state.dart';
import '../bloc/announcement_detail_cubit.dart';
import '../bloc/feed_cubit.dart';
import '../data/feed_models.dart';
import 'feed_detail_screen.dart';

/// The feed tab.
class FeedScreen extends StatelessWidget {
  /// Creates the screen.
  const FeedScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isAuthenticated = context.select<AuthBloc, bool>(
      (bloc) => bloc.state.status == AuthStatus.authenticated,
    );

    if (!isAuthenticated) {
      // The personalised feed is a T1 capability; Guests browse find-my-rep.
      return EmptyView(message: l10n.feedSignInPrompt, icon: Icons.login);
    }

    return BlocBuilder<FeedCubit, FeedState>(
      builder: (context, state) {
        switch (state.status) {
          case FeedStatus.initial:
          case FeedStatus.loading:
            return LoadingView(label: l10n.loadingLabel);
          case FeedStatus.failure:
            return ErrorRetryView(
              message: FailureMessages.of(l10n, state.error!),
              retryLabel: l10n.retryButton,
              onRetry: () => context.read<FeedCubit>().load(),
            );
          case FeedStatus.loaded:
            if (state.items.isEmpty) {
              return EmptyView(message: l10n.feedEmpty);
            }
            return RefreshIndicator(
              onRefresh: () => context.read<FeedCubit>().load(),
              child: ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: state.items.length,
                separatorBuilder: (_, _) => const SizedBox(height: 8),
                itemBuilder: (context, i) => _FeedTile(item: state.items[i]),
              ),
            );
        }
      },
    );
  }
}

/// A single, const-friendly feed row.
class _FeedTile extends StatelessWidget {
  const _FeedTile({required this.item});

  final FeedItem item;

  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    child: ListTile(
      title: Text(item.title, maxLines: 2, overflow: TextOverflow.ellipsis),
      subtitle: Text(
        item.snippet,
        maxLines: 3,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: const Icon(Icons.chevron_right),
      // Open the full announcement on tap (US-4.2). The detail seeds from the
      // snippet already in hand (instant, offline-safe) and fetches the full body
      // via GET /announcements/{id} over the shared feed repository + its cache.
      onTap: () {
        final repository = context.read<FeedCubit>().repository;
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => BlocProvider(
              create: (_) => AnnouncementDetailCubit(
                repository: repository,
                announcementId: item.id,
              )..load(),
              child: FeedDetailScreen(item: item),
            ),
          ),
        );
      },
    ),
  );
}
