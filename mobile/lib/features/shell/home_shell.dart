/// The signed-in shell: a bottom-nav scaffold hosting the Home/Feed and
/// Find-my-representatives tabs, a "Report an issue" call-to-action, and a drawer
/// to the rest of the citizen surfaces (My reports, Engage, Notifications,
/// Profile). A sign-out action lives in the app bar.
///
/// Feed and find-my-rep cubits are provided here (lazily) so they live for the
/// shell's lifetime and survive tab switches. The other feature screens are
/// pushed as routes, each providing its own scoped cubit from the shared
/// repositories so their state is fresh per visit and cheap when not open.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../core/di/app_dependencies.dart';
import '../../l10n/app_localizations.dart';
import '../auth/bloc/auth_bloc.dart';
import '../auth/bloc/auth_event.dart';
import '../engagement/bloc/engagement_cubit.dart';
import '../engagement/view/engagement_screen.dart';
import '../feed/bloc/feed_cubit.dart';
import '../feed/view/feed_screen.dart';
import '../notifications/bloc/notification_prefs_cubit.dart';
import '../notifications/bloc/notifications_cubit.dart';
import '../notifications/view/notification_prefs_screen.dart';
import '../notifications/view/notifications_screen.dart';
import '../profile/bloc/profile_cubit.dart';
import '../profile/view/profile_screen.dart';
import '../reporting/bloc/my_reports_cubit.dart';
import '../reporting/bloc/report_detail_cubit.dart';
import '../reporting/bloc/report_form_cubit.dart';
import '../reporting/view/my_reports_screen.dart';
import '../reporting/view/report_detail_screen.dart';
import '../reporting/view/report_form_screen.dart';
import '../representatives/bloc/my_reps_cubit.dart';
import '../representatives/view/my_reps_screen.dart';

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
  int _index = 0;

  AppDependencies get _deps => widget.dependencies;

  // --- Navigation to the route-pushed feature screens ----------------------

  /// Opens the file-a-report form (US-3.1), preloading the category picker.
  Future<void> _openReportForm() async {
    final filed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) => ReportFormCubit(
            categoryRepository: _deps.categoryRepository,
            reportRepository: _deps.reportRepository,
          )..loadCategories(),
          child: ReportFormScreen(dependencies: _deps),
        ),
      ),
    );
    // After a successful file/queue, jump the citizen to their reports.
    if (filed == true && mounted) {
      _openMyReports();
    }
  }

  /// Opens the citizen's own reports + offline drafts (US-3.2).
  void _openMyReports() {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BlocProvider(
          create: (_) =>
              MyReportsCubit(repository: _deps.reportRepository)..load(),
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
          child: ProfileScreen(dependencies: _deps),
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
          create: (_) =>
              FeedCubit(repository: _deps.feedRepository)..load(),
        ),
        BlocProvider<MyRepsCubit>(
          create: (_) =>
              MyRepsCubit(repository: _deps.representativeRepository),
        ),
      ],
      child: Scaffold(
        appBar: AppBar(
          title: Text(_index == 0 ? l10n.feedTitle : l10n.myRepsTitle),
          actions: [
            IconButton(
              tooltip: l10n.navNotifications,
              icon: const Icon(Icons.notifications_outlined),
              onPressed: _openNotifications,
            ),
            IconButton(
              tooltip: l10n.logoutButton,
              icon: const Icon(Icons.logout),
              onPressed: () =>
                  context.read<AuthBloc>().add(const AuthLoggedOut()),
            ),
          ],
        ),
        drawer: _buildDrawer(context, l10n),
        body: IndexedStack(
          index: _index,
          children: [const FeedScreen(), MyRepsScreen(dependencies: _deps)],
        ),
        floatingActionButton: FloatingActionButton.extended(
          onPressed: _openReportForm,
          icon: const Icon(Icons.report_outlined),
          label: Text(l10n.navReport),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: [
            NavigationDestination(
              icon: const Icon(Icons.home_outlined),
              selectedIcon: const Icon(Icons.home),
              label: l10n.navHome,
            ),
            NavigationDestination(
              icon: const Icon(Icons.account_balance_outlined),
              selectedIcon: const Icon(Icons.account_balance),
              label: l10n.navMyReps,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDrawer(BuildContext context, AppLocalizations l10n) {
    return Drawer(
      child: SafeArea(
        child: ListView(
          children: [
            DrawerHeader(
              child: Align(
                alignment: Alignment.bottomLeft,
                child: Text(
                  l10n.appTitle,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
            ),
            _drawerItem(Icons.report_outlined, l10n.navReport, () {
              Navigator.of(context).pop();
              _openReportForm();
            }),
            _drawerItem(Icons.list_alt_outlined, l10n.navMyReports, () {
              Navigator.of(context).pop();
              _openMyReports();
            }),
            _drawerItem(Icons.how_to_vote_outlined, l10n.navEngage, () {
              Navigator.of(context).pop();
              _openEngage();
            }),
            _drawerItem(
              Icons.notifications_outlined,
              l10n.navNotifications,
              () {
                Navigator.of(context).pop();
                _openNotifications();
              },
            ),
            _drawerItem(Icons.person_outline, l10n.navProfile, () {
              Navigator.of(context).pop();
              _openProfile();
            }),
          ],
        ),
      ),
    );
  }

  Widget _drawerItem(IconData icon, String label, VoidCallback onTap) =>
      ListTile(leading: Icon(icon), title: Text(label), onTap: onTap);
}
