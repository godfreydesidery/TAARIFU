/// The home/feed screen: the citizen's personalised civic stream.
///
/// Consumes `GET /feed` via [FeedCubit]. The feed needs a session (T1); for a
/// Guest it shows a sign-in prompt instead of a failing call. Handles all four
/// states: loading (an elegant shimmer skeleton, not a bare spinner), loaded
/// (rich [FeedCard]s with a friendly greeting header and a staggered fade-in),
/// empty, and error/offline.
///
/// Data-frugal (PRD §15): cover imagery on cards is suppressed when the citizen's
/// data-saver is on (read from [SettingsCubit]); the offline/cache behaviour in
/// [FeedRepository] is untouched — this is a pure presentation refresh.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/settings/settings_cubit.dart';
import '../../../core/theme/app_palette.dart';
import '../../../core/widgets/shimmer.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/bloc/auth_bloc.dart';
import '../../auth/bloc/auth_state.dart';
import '../bloc/announcement_detail_cubit.dart';
import '../bloc/feed_cubit.dart';
import '../data/feed_models.dart';
import 'feed_card.dart';
import 'feed_detail_screen.dart';

/// The feed tab.
class FeedScreen extends StatelessWidget {
  /// Creates the screen. [onSignIn] is invoked from the Guest prompt (the shell
  /// routes it to sign-in); when omitted the prompt shows without an action.
  /// [onOpenEngagement], when supplied, is the inline CTA target for actionable
  /// petition/poll cards (opens the engagement hub where the real action lives).
  const FeedScreen({this.onSignIn, this.onOpenEngagement, super.key});

  /// Optional sign-in callback for the Guest empty state.
  final VoidCallback? onSignIn;

  /// Optional engagement-hub opener for petition/poll inline CTAs.
  final VoidCallback? onOpenEngagement;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final isAuthenticated = context.select<AuthBloc, bool>(
      (bloc) => bloc.state.status == AuthStatus.authenticated,
    );
    final dataSaver = context.select<SettingsCubit, bool>(
      (cubit) => cubit.state.dataSaver,
    );

    if (!isAuthenticated) {
      // The personalised feed is a T1 capability; Guests browse find-my-rep.
      return EmptyView(
        message: l10n.feedSignInPrompt,
        icon: Icons.lock_open_rounded,
        actionLabel: onSignIn != null ? l10n.feedSignInButton : null,
        onAction: onSignIn,
      );
    }

    return BlocBuilder<FeedCubit, FeedState>(
      builder: (context, state) {
        switch (state.status) {
          case FeedStatus.initial:
          case FeedStatus.loading:
            return const _FeedSkeleton();
          case FeedStatus.failure:
            return ErrorRetryView(
              message: FailureMessages.of(l10n, state.error!),
              retryLabel: l10n.retryButton,
              onRetry: () => context.read<FeedCubit>().load(),
            );
          case FeedStatus.loaded:
            if (state.items.isEmpty) {
              return EmptyView(message: l10n.feedEmpty, icon: Icons.feed_outlined);
            }
            return RefreshIndicator(
              onRefresh: () => context.read<FeedCubit>().load(),
              child: ListView.separated(
                padding: const EdgeInsets.fromLTRB(
                  AppPalette.spaceMd,
                  AppPalette.spaceSm,
                  AppPalette.spaceMd,
                  // Bottom padding clears the floating "report" FAB.
                  96,
                ),
                // +1 leading slot for the greeting header.
                itemCount: state.items.length + 1,
                separatorBuilder: (_, _) =>
                    const SizedBox(height: AppPalette.spaceMd),
                itemBuilder: (context, i) {
                  if (i == 0) return const _GreetingHeader();
                  final item = state.items[i - 1];
                  return _FadeInItem(
                    // A gentle, index-staggered fade so the list arrives with a
                    // soft cascade rather than popping in all at once.
                    delayMs: ((i - 1) * 40).clamp(0, 280),
                    child: FeedCard(
                      key: ValueKey(item.id),
                      item: item,
                      dataSaver: dataSaver,
                      onTap: () => _openDetail(context, item),
                      // Petition/poll cards get an inline Sign/Respond CTA that
                      // opens the engagement hub (the real, tier-gated action).
                      onAct: _isActionable(item.kind)
                          ? onOpenEngagement
                          : null,
                    ),
                  );
                },
              ),
            );
        }
      },
    );
  }

  /// Whether a feed item's kind carries an inline civic action (petition/poll).
  bool _isActionable(FeedItemKind kind) =>
      kind == FeedItemKind.petition || kind == FeedItemKind.poll;

  /// Opens the full announcement on tap (US-4.2). The detail seeds from the
  /// snippet already in hand (instant, offline-safe) and fetches the full body
  /// via GET /announcements/{id} over the shared feed repository + its cache.
  void _openDetail(BuildContext context, FeedItem item) {
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
  }
}

/// A warm, time-aware greeting above the feed (Habari za asubuhi/…). Purely
/// presentational and Swahili-first; gives the home a personal, social feel.
class _GreetingHeader extends StatelessWidget {
  const _GreetingHeader();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final hour = DateTime.now().hour;
    final greeting = hour < 12
        ? l10n.feedGreetingMorning
        : hour < 17
        ? l10n.feedGreetingAfternoon
        : l10n.feedGreetingEvening;
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AppPalette.spaceXs,
        AppPalette.spaceMd,
        AppPalette.spaceXs,
        AppPalette.spaceXs,
      ),
      child: Text(
        greeting,
        style: Theme.of(context).textTheme.headlineSmall,
      ),
    );
  }
}

/// The loading skeleton: a stack of shimmering feed-card placeholders that hint
/// the content's shape so a slow 2G load feels responsive (not a bare spinner).
class _FeedSkeleton extends StatelessWidget {
  const _FeedSkeleton();

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(AppPalette.spaceMd),
    physics: const NeverScrollableScrollPhysics(),
    children: const [
      FeedCardSkeleton(),
      SizedBox(height: AppPalette.spaceMd),
      FeedCardSkeleton(),
      SizedBox(height: AppPalette.spaceMd),
      FeedCardSkeleton(),
    ],
  );
}

/// Wraps [child] in a brief fade+rise entrance, staggered by [delayMs].
class _FadeInItem extends StatefulWidget {
  const _FadeInItem({required this.child, required this.delayMs});

  final Widget child;
  final int delayMs;

  @override
  State<_FadeInItem> createState() => _FadeInItemState();
}

class _FadeInItemState extends State<_FadeInItem>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 320),
  );
  late final Animation<double> _fade = CurvedAnimation(
    parent: _controller,
    curve: Curves.easeOut,
  );

  @override
  void initState() {
    super.initState();
    Future<void>.delayed(Duration(milliseconds: widget.delayMs), () {
      if (mounted) _controller.forward();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FadeTransition(
    opacity: _fade,
    child: SlideTransition(
      position: Tween<Offset>(
        begin: const Offset(0, 0.04),
        end: Offset.zero,
      ).animate(_fade),
      child: widget.child,
    ),
  );
}
