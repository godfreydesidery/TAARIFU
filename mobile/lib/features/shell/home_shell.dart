/// The signed-in shell: a modern bottom-nav scaffold with a centre-docked
/// "Report" action — the elegant, social-app home for a signed-in citizen.
///
/// The bottom bar has four destinations around a central [FloatingActionButton]:
///   Feed (Mwanzo) · Track (Fuatilia) | [Report] | Notifications (Arifa) ·
///   Profile (Wasifu).
/// Feed and Find-my-rep live in an [IndexedStack] so their cubits (provided
/// here, lazily) survive tab switches; Track, Notifications, and Profile are
/// pushed as routes from the bar, each providing its own scoped cubit from the
/// shared repositories so their state is fresh per visit and cheap when closed.
/// This keeps every feature screen's own Scaffold/AppBar intact (no double app
/// bar) while giving the home the rich five-way navigation citizens expect.
///
/// This is a presentation refresh only: every bloc/cubit, route, repository, and
/// the push deep-link wiring are unchanged.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../core/di/app_dependencies.dart';
import '../../core/theme/app_palette.dart';
import '../../l10n/app_localizations.dart';
import '../auth/bloc/auth_bloc.dart';
import '../auth/bloc/auth_event.dart';
import '../engagement/bloc/engagement_cubit.dart';
import '../engagement/view/engagement_screen.dart';
import '../feed/bloc/announcement_detail_cubit.dart';
import '../feed/bloc/feed_cubit.dart';
import '../feed/data/feed_models.dart';
import '../feed/view/feed_detail_screen.dart';
import '../feed/view/feed_screen.dart';
import '../notifications/bloc/notification_prefs_cubit.dart';
import '../notifications/bloc/notifications_cubit.dart';
import '../notifications/data/push_service.dart';
import '../notifications/view/notification_prefs_screen.dart';
import '../notifications/view/notifications_screen.dart';
import '../privacy/bloc/dsr_cubit.dart';
import '../privacy/view/dsr_screen.dart';
import '../profile/bloc/profile_cubit.dart';
import '../profile/view/profile_screen.dart';
import '../search/bloc/search_cubit.dart';
import '../search/data/search_models.dart';
import '../search/view/search_screen.dart';
import '../reporting/bloc/my_reports_cubit.dart';
import '../reporting/bloc/report_detail_cubit.dart';
import '../reporting/bloc/report_form_cubit.dart';
import '../reporting/view/my_reports_screen.dart';
import '../reporting/view/report_detail_screen.dart';
import '../reporting/view/report_form_screen.dart';
import '../representatives/bloc/my_reps_cubit.dart';
import '../representatives/view/my_reps_screen.dart';
import '../settings/view/settings_screen.dart';

/// The signed-in shell.
class HomeShell extends StatefulWidget {
  /// Creates the shell over the app [dependencies].
  const HomeShell({required this.dependencies, super.key});

  /// The composition root (for repository access).
  final AppDependencies dependencies;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  /// 0 = Feed, 1 = Find-my-rep (the two IndexedStack tabs).
  int _index = 0;
  StreamSubscription<NotificationDeepLink>? _pushSub;

  AppDependencies get _deps => widget.dependencies;

  @override
  void initState() {
    super.initState();
    // Register the device for push (no-op until firebase_messaging is wired) and
    // route notification taps into the app: a REPORT_STATUS tap opens that
    // report's timeline (deep-link tap-through, US-5.1). Unknown types open the
    // inbox so a tap never dead-ends.
    final push = _deps.pushService;
    unawaited(push.registerToken());
    _pushSub = push.onMessageOpened.listen(_handleDeepLink);
  }

  @override
  void dispose() {
    _pushSub?.cancel();
    super.dispose();
  }

  /// Routes a notification tap to the right surface.
  void _handleDeepLink(NotificationDeepLink link) {
    if (!mounted) return;
    if (link.isReport) {
      _openReportDetail(link.targetId!);
    } else {
      _openNotifications();
    }
  }

  // --- Navigation to the route-pushed feature screens ----------------------

  /// Opens the file-a-report form (US-3.1), preloading the category picker.
  Future<void> _openReportForm() async {
    final filed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => ReportFormCubit(
            categoryRepository: _deps.categoryRepository,
            reportRepository: _deps.reportRepository,
            attachmentService: _deps.attachmentService,
          )..loadCategories(),
          child: ReportFormScreen(
            geographyRepository: _deps.geographyRepository,
          ),
        ),
      ),
    );
    // After a successful file/queue, jump the citizen to their reports.
    if (filed == true && mounted) {
      _openMyReports();
    }
  }

  /// Opens the citizen's own reports + offline drafts (US-3.2).
  ///
  /// The cubit takes the shared connectivity hint so the offline outbox
  /// auto-flushes when a bar reappears (retry-on-reconnect; the idempotency key
  /// keeps replays duplicate-free).
  void _openMyReports() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => MyReportsCubit(
            repository: _deps.reportRepository,
            connectivity: _deps.connectivity,
          )..load(),
          child: MyReportsScreen(onOpenReport: _openReportDetail),
        ),
      ),
    );
  }

  /// Opens a report's tracking/timeline detail (US-3.2, US-3.5).
  void _openReportDetail(String reportId) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => ReportDetailCubit(
            repository: _deps.reportRepository,
            reportId: reportId,
          )..load(),
          child: const ReportDetailScreen(),
        ),
      ),
    );
  }

  /// Opens the engagement hub (petitions/surveys/Q&A).
  void _openEngage() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => EngagementCubit(
            repository: _deps.engagementRepository,
            profileRepository: _deps.profileRepository,
          )..loadAll(),
          child: EngagementScreen(onNeedVerification: _openProfile),
        ),
      ),
    );
  }

  /// Opens the profile + verification screen.
  void _openProfile() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) =>
              ProfileCubit(repository: _deps.profileRepository)..load(),
          child: ProfileScreen(
            geographyRepository: _deps.geographyRepository,
            onOpenDataRights: _openDataRights,
          ),
        ),
      ),
    );
  }

  /// Opens the Settings screen (language, appearance, data-saver, sign out).
  void _openSettings() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SettingsScreen(
          appVersion: _deps.appConfig.appVersion,
          onSignOut: () {
            // Clear any push token then wipe the session (secure logout).
            unawaited(_deps.pushService.clearToken());
            context.read<AuthBloc>().add(const AuthLoggedOut());
            Navigator.of(context).popUntil((r) => r.isFirst);
          },
        ),
      ),
    );
  }

  /// Opens the cross-entity discovery search (PRD discovery, ADR-0017).
  void _openSearch() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => SearchCubit(repository: _deps.searchRepository),
          child: SearchScreen(onOpenResult: _openSearchResult),
        ),
      ),
    );
  }

  /// Routes a tapped search result to its owning surface. Returns `true` when a
  /// destination exists (so the search screen knows whether to nudge the citizen).
  ///
  /// Today only an ANNOUNCEMENT has a citizen-facing detail screen; other kinds
  /// (rep/org/category/public report) await their own screens and return `false`
  /// rather than dead-ending or fabricating a destination (EI-7, fail-safe).
  bool _openSearchResult(SearchResult result) {
    if (result.kind == SearchResultKind.announcement &&
        result.entityPublicId.isNotEmpty) {
      _openAnnouncement(result.entityPublicId, result.title, result.snippet);
      return true;
    }
    return false;
  }

  /// Opens a full announcement by id, seeding the detail from a lean [FeedItem]
  /// (built from the search result) so the title shows instantly while the body
  /// loads (US-4.2). Reuses the existing feed detail + its repository/cache.
  void _openAnnouncement(String id, String title, String? snippet) {
    final item = FeedItem(id: id, title: title, snippet: snippet ?? '');
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => BlocProvider(
          create: (_) => AnnouncementDetailCubit(
            repository: _deps.feedRepository,
            announcementId: id,
          )..load(),
          child: FeedDetailScreen(item: item),
        ),
      ),
    );
  }

  /// Opens the PDPA data-request (DSR) self-service screen.
  void _openDataRights() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => DsrCubit(repository: _deps.dsrRepository)..load(),
          child: const DsrScreen(),
        ),
      ),
    );
  }

  /// Opens the notification inbox.
  void _openNotifications() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) =>
              NotificationsCubit(repository: _deps.notificationRepository)
                ..load(),
          child: NotificationsScreen(onOpenPrefs: _openNotificationPrefs),
        ),
      ),
    );
  }

  /// Opens the notification-preferences screen.
  void _openNotificationPrefs() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) =>
              NotificationPrefsCubit(repository: _deps.notificationRepository)
                ..load(),
          child: const NotificationPrefsScreen(),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return MultiBlocProvider(
      providers: [
        BlocProvider<FeedCubit>(
          create: (_) => FeedCubit(repository: _deps.feedRepository)..load(),
        ),
        BlocProvider<MyRepsCubit>(
          create: (_) =>
              MyRepsCubit(repository: _deps.representativeRepository),
        ),
      ],
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: AppPalette.spaceLg,
          title: Text(_index == 0 ? l10n.feedTitle : l10n.myRepsTitle),
          actions: [
            IconButton(
              tooltip: l10n.navSearch,
              icon: const Icon(Icons.search_rounded),
              onPressed: _openSearch,
            ),
            IconButton(
              tooltip: l10n.navEngage,
              icon: const Icon(Icons.how_to_vote_outlined),
              onPressed: _openEngage,
            ),
            IconButton(
              tooltip: l10n.navSettings,
              icon: const Icon(Icons.settings_outlined),
              onPressed: _openSettings,
            ),
          ],
        ),
        body: IndexedStack(
          index: _index,
          children: [
            FeedScreen(
              onSignIn: _openSettings,
              onOpenEngagement: _openEngage,
            ),
            MyRepsScreen(geographyRepository: _deps.geographyRepository),
          ],
        ),
        // The centre-docked "Report" action — the app's single most important
        // civic gesture (US-3.1), in the unmistakable amber accent.
        floatingActionButtonLocation:
            FloatingActionButtonLocation.centerDocked,
        floatingActionButton: FloatingActionButton(
          onPressed: _openReportForm,
          tooltip: l10n.navReport,
          shape: const CircleBorder(),
          child: const Icon(Icons.add_rounded, size: 30),
        ),
        bottomNavigationBar: _BottomBar(
          index: _index,
          onFeed: () => setState(() => _index = 0),
          onReps: () => setState(() => _index = 1),
          onTrack: _openMyReports,
          onNotifications: _openNotifications,
          onProfile: _openProfile,
        ),
      ),
    );
  }
}

/// The custom bottom navigation bar: a [BottomAppBar] notched around the central
/// Report FAB, with two destinations on each side. We use a [BottomAppBar] (not
/// [NavigationBar]) precisely so the FAB can dock into a notch — the hallmark of
/// the social-app home — while keeping large, labelled, high-contrast tap targets
/// for low-end touchscreens (PRD §14).
class _BottomBar extends StatelessWidget {
  const _BottomBar({
    required this.index,
    required this.onFeed,
    required this.onReps,
    required this.onTrack,
    required this.onNotifications,
    required this.onProfile,
  });

  /// The active IndexedStack tab (0 = Feed, 1 = Find-my-rep). The route-pushed
  /// destinations (Track/Notifications/Profile) are never the "selected" tab.
  final int index;
  final VoidCallback onFeed;
  final VoidCallback onReps;
  final VoidCallback onTrack;
  final VoidCallback onNotifications;
  final VoidCallback onProfile;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return BottomAppBar(
      height: 68,
      padding: EdgeInsets.zero,
      shape: const CircularNotchedRectangle(),
      notchMargin: 8,
      color: Theme.of(context).colorScheme.surface,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _BarItem(
            icon: Icons.home_outlined,
            activeIcon: Icons.home_rounded,
            label: l10n.navHome,
            selected: index == 0,
            onTap: onFeed,
          ),
          _BarItem(
            icon: Icons.account_balance_outlined,
            activeIcon: Icons.account_balance_rounded,
            label: l10n.navMyReps,
            selected: index == 1,
            onTap: onReps,
          ),
          // Spacer for the docked FAB notch.
          const SizedBox(width: 56),
          _BarItem(
            icon: Icons.track_changes_outlined,
            activeIcon: Icons.track_changes_rounded,
            label: l10n.navTrack,
            selected: false,
            onTap: onTrack,
          ),
          _BarItem(
            icon: Icons.notifications_outlined,
            activeIcon: Icons.notifications_rounded,
            label: l10n.navNotifications,
            selected: false,
            onTap: onNotifications,
          ),
          _BarItem(
            icon: Icons.person_outline_rounded,
            activeIcon: Icons.person_rounded,
            label: l10n.navProfile,
            selected: false,
            onTap: onProfile,
          ),
        ],
      ),
    );
  }
}

/// A single bottom-bar destination: an icon over a tiny label, tinted when
/// selected. A flexible item so five fit comfortably on a narrow, low-end screen.
class _BarItem extends StatelessWidget {
  const _BarItem({
    required this.icon,
    required this.activeIcon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final IconData activeIcon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = selected ? scheme.primary : scheme.onSurfaceVariant;
    return Expanded(
      child: InkResponse(
        onTap: onTap,
        radius: 36,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(selected ? activeIcon : icon, size: 24, color: color),
            const SizedBox(height: 2),
            Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: color,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
