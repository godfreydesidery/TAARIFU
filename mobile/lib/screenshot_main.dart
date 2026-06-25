/// Dev-only screenshot entrypoint: renders ONE redesigned ("social app") screen
/// chosen by the URL fragment, with realistic Tanzanian civic content and the
/// REAL theme + Swahili-first localization, so a headless browser can capture the
/// elegant journey as true PNGs.
///
/// WHY a separate entrypoint (not the integration_test screenshot pipeline): on
/// Flutter web the integration_test `takeScreenshot`/`convertFlutterSurfaceToImage`
/// path hangs, so we render the real widgets in a normal web app and let the
/// browser screenshot the canvas instead. Plugin-free fake repositories feed the
/// cubits canned data; nothing here ships in the citizen app (it is never the
/// production `main`).
///
/// Pick the screen with `?screen=` or the hash fragment, e.g.
///   http://127.0.0.1:5101/#otp        http://127.0.0.1:5101/#feed
///   #reps  #reports  #notifications  #profile  #feed-dark  #onboarding
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/theme/app_theme.dart';
import 'core/theme/app_palette.dart';
import 'core/storage/outbox_entry.dart';
import 'core/settings/app_settings.dart';
import 'core/settings/settings_cubit.dart';
import 'core/settings/settings_store.dart';

import 'features/auth/bloc/auth_bloc.dart';
import 'features/auth/bloc/auth_event.dart';
import 'features/auth/data/auth_models.dart';
import 'features/auth/data/auth_repository.dart';
import 'features/auth/view/onboarding_screen.dart';

import 'features/feed/bloc/feed_cubit.dart';
import 'features/feed/data/feed_models.dart';
import 'features/feed/data/feed_repository.dart';
import 'features/feed/view/feed_screen.dart';

import 'features/notifications/bloc/notifications_cubit.dart';
import 'features/notifications/data/notification_models.dart';
import 'features/notifications/data/notification_repository.dart';
import 'features/notifications/view/notifications_screen.dart';

import 'features/profile/bloc/profile_cubit.dart';
import 'features/profile/data/profile_models.dart';
import 'features/profile/data/profile_repository.dart';
import 'features/profile/view/profile_screen.dart';

import 'features/reporting/bloc/my_reports_cubit.dart';
import 'features/reporting/data/report_repository.dart';
import 'features/reporting/data/reporting_models.dart';
import 'features/reporting/view/my_reports_screen.dart';

import 'features/representatives/bloc/my_reps_cubit.dart';
import 'features/representatives/data/representative_models.dart';
import 'features/representatives/data/representative_repository.dart';
import 'features/representatives/view/my_reps_screen.dart';

import 'features/geography/data/geography_repository.dart';

import 'l10n/app_localizations.dart';

DateTime _ago(Duration d) => DateTime.now().toUtc().subtract(d);

void main() {
  runApp(const _ScreenshotApp());
}

/// Reads the requested screen from the URL fragment (e.g. `#feed`).
String _requestedScreen() {
  final frag = Uri.base.fragment;
  if (frag.isNotEmpty) return frag.replaceAll('/', '');
  return Uri.base.queryParameters['screen'] ?? 'onboarding';
}

class _ScreenshotApp extends StatelessWidget {
  const _ScreenshotApp();

  @override
  Widget build(BuildContext context) {
    final screen = _requestedScreen();
    final dark = screen.endsWith('-dark');
    // Allow an explicit locale override (?lang=en) so we can capture the English
    // onboarding variant; defaults to Swahili-first (PRD §14).
    final lang = Uri.base.queryParameters['lang'] == 'en' ? 'en' : 'sw';
    // Global providers every authenticated screen needs: a SettingsCubit (the
    // feed reads data-saver from it) and an AUTHENTICATED AuthBloc (the feed is a
    // T1 capability and shows a sign-in prompt otherwise). The onboarding/OTP
    // screens nest their own AuthBloc to drive the pre-auth states.
    return MultiBlocProvider(
      providers: [
        BlocProvider<SettingsCubit>(
          create: (_) => SettingsCubit(
            store: InMemorySettingsStore(),
            initial: AppSettings(
              themeMode: dark ? ThemeMode.dark : ThemeMode.light,
            ),
          ),
        ),
        BlocProvider<AuthBloc>(
          create: (_) =>
              AuthBloc(repository: _AuthedAuthRepository())
                ..add(const AuthStarted()),
        ),
      ],
      child: MaterialApp(
        debugShowCheckedModeBanner: false,
        onGenerateTitle: (c) => AppLocalizations.of(c).appTitle,
        theme: AppTheme.light(),
        darkTheme: AppTheme.dark(),
        themeMode: dark ? ThemeMode.dark : ThemeMode.light,
        locale: Locale(lang),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: _Router(screen: screen),
      ),
    );
  }
}

class _Router extends StatelessWidget {
  const _Router({required this.screen});
  final String screen;

  @override
  Widget build(BuildContext context) {
    switch (screen) {
      case 'otp':
        return const _OtpScreen();
      case 'feed':
      case 'feed-dark':
        return _SocialHome(feedRepo: _FakeFeedRepository());
      case 'reps':
        return const _RepsScreen();
      case 'reports':
        return const _ReportsScreen();
      case 'notifications':
        return const _NotificationsScreen();
      case 'profile':
        return const _ProfileScreen();
      case 'onboarding':
      default:
        return _OnboardingHost();
    }
  }
}

// --- Onboarding (phone step) -------------------------------------------------

class _OnboardingHost extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return BlocProvider<AuthBloc>(
      create: (_) =>
          AuthBloc(repository: _OtpAuthRepository())..add(const AuthStarted()),
      child: const OnboardingScreen(),
    );
  }
}

class _OtpScreen extends StatelessWidget {
  const _OtpScreen();
  @override
  Widget build(BuildContext context) {
    final auth = AuthBloc(repository: _OtpAuthRepository())
      ..add(const AuthStarted())
      ..add(const AuthOtpRequested('+255712345678'));
    return BlocProvider<AuthBloc>.value(
      value: auth,
      child: const OnboardingScreen(),
    );
  }
}

// --- Social feed home (the headline redesign) --------------------------------

class _SocialHome extends StatelessWidget {
  const _SocialHome({required this.feedRepo});
  final FeedRepository feedRepo;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    return BlocProvider<FeedCubit>(
      create: (_) => FeedCubit(repository: feedRepo)..load(),
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: AppPalette.spaceLg,
          title: Text(l10n.feedTitle),
          actions: [
            IconButton(
                icon: const Icon(Icons.how_to_vote_outlined),
                onPressed: () {}),
            IconButton(
                icon: const Icon(Icons.settings_outlined), onPressed: () {}),
          ],
        ),
        body: const FeedScreen(),
        floatingActionButtonLocation:
            FloatingActionButtonLocation.centerDocked,
        floatingActionButton: FloatingActionButton(
          onPressed: () {},
          tooltip: l10n.navReport,
          shape: const CircleBorder(),
          child: const Icon(Icons.add_rounded, size: 30),
        ),
        bottomNavigationBar: BottomAppBar(
          height: 68,
          padding: EdgeInsets.zero,
          shape: const CircularNotchedRectangle(),
          notchMargin: 8,
          color: scheme.surface,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              _bar(context, Icons.home_rounded, l10n.navHome, true),
              _bar(context, Icons.account_balance_outlined, l10n.navMyReps,
                  false),
              const SizedBox(width: 56),
              _bar(context, Icons.track_changes_outlined, l10n.navTrack, false),
              _bar(context, Icons.notifications_outlined, l10n.navNotifications,
                  false),
              _bar(context, Icons.person_outline_rounded, l10n.navProfile,
                  false),
            ],
          ),
        ),
      ),
    );
  }

  Widget _bar(BuildContext c, IconData icon, String label, bool selected) {
    final scheme = Theme.of(c).colorScheme;
    final color = selected ? scheme.primary : scheme.onSurfaceVariant;
    return Expanded(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 24, color: color),
          const SizedBox(height: 2),
          Text(label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(c).textTheme.labelSmall?.copyWith(
                  color: color,
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500)),
        ],
      ),
    );
  }
}

// --- Other bottom-nav destinations -------------------------------------------

class _RepsScreen extends StatelessWidget {
  const _RepsScreen();
  @override
  Widget build(BuildContext context) {
    final cubit = MyRepsCubit(repository: _FakeRepRepository())
      ..loadForWard('ward-1');
    return Scaffold(
      appBar: AppBar(
          title: Builder(
              builder: (c) => Text(AppLocalizations.of(c).myRepsTitle))),
      body: BlocProvider<MyRepsCubit>.value(
        value: cubit,
        child: MyRepsScreen(geographyRepository: _FakeGeographyRepository()),
      ),
    );
  }
}

class _ReportsScreen extends StatelessWidget {
  const _ReportsScreen();
  @override
  Widget build(BuildContext context) {
    final cubit = MyReportsCubit(repository: _FakeReportRepository())..load();
    return BlocProvider<MyReportsCubit>.value(
      value: cubit,
      child: MyReportsScreen(onOpenReport: (_) {}),
    );
  }
}

class _NotificationsScreen extends StatelessWidget {
  const _NotificationsScreen();
  @override
  Widget build(BuildContext context) {
    final cubit =
        NotificationsCubit(repository: _FakeNotificationRepository())..load();
    return BlocProvider<NotificationsCubit>.value(
      value: cubit,
      child: NotificationsScreen(onOpenPrefs: () {}),
    );
  }
}

class _ProfileScreen extends StatelessWidget {
  const _ProfileScreen();
  @override
  Widget build(BuildContext context) {
    final cubit = ProfileCubit(repository: _FakeProfileRepository())..load();
    return BlocProvider<ProfileCubit>.value(
      value: cubit,
      child: ProfileScreen(geographyRepository: _FakeGeographyRepository()),
    );
  }
}

// --- Fake repositories -------------------------------------------------------

class _FakeFeedRepository implements FeedRepository {
  @override
  Future<List<FeedItem>> loadFirstPage() async => [
        FeedItem(
          id: '1',
          kind: FeedItemKind.announcement,
          title: 'Maji yatakatika kwa matengenezo Kata ya Kinondoni',
          snippet:
              'DAWASA inafahamisha wakazi kuwa huduma ya maji itasimama Jumatano '
              'kuanzia saa 2 asubuhi hadi saa 8 mchana kwa ajili ya matengenezo ya bomba kuu.',
          authorName: 'Halmashauri ya Manispaa ya Kinondoni',
          areaName: 'Kata ya Kinondoni',
          publishedAt: _ago(const Duration(hours: 2)),
          reactionCount: 48,
        ),
        FeedItem(
          id: '2',
          kind: FeedItemKind.report,
          title: 'Taa za barabarani zimezimika Mtaa wa Mwananyamala',
          snippet:
              'Ripoti ya mwananchi: taa za barabarani hazifanyi kazi kwa wiki mbili, '
              'eneo ni giza nyakati za usiku na si salama kwa watembea kwa miguu.',
          authorName: 'Mwananchi',
          areaName: 'Mwananyamala',
          publishedAt: _ago(const Duration(hours: 6)),
          reactionCount: 17,
        ),
        FeedItem(
          id: '3',
          kind: FeedItemKind.petition,
          title: 'Ombi: Kituo cha afya Kata ya Tabata',
          snippet:
              'Wakazi wanaomba kujengwa kwa kituo cha afya cha kata ili kupunguza '
              'umbali wa kufuata huduma za matibabu. Saini ili kuunga mkono.',
          authorName: 'Kamati ya Maendeleo ya Kata',
          areaName: 'Kata ya Tabata',
          publishedAt: _ago(const Duration(days: 1)),
          reactionCount: 312,
        ),
        FeedItem(
          id: '4',
          kind: FeedItemKind.poll,
          title: 'Kura ya maoni: Kipaumbele cha bajeti ya kata 2026',
          snippet:
              'Ni huduma gani ipewe kipaumbele mwaka huu? Barabara, maji, au elimu? '
              'Toa maoni yako kwa dakika moja.',
          authorName: 'Ofisi ya Mtendaji wa Kata',
          areaName: 'Kata ya Ubungo',
          publishedAt: _ago(const Duration(days: 2)),
          reactionCount: 95,
        ),
      ];

  @override
  Future<Announcement> getAnnouncement(String id) async =>
      Announcement(id: id, title: '', bodySw: '');

  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

class _FakeReportRepository implements ReportRepository {
  @override
  Future<List<Report>> listMine({int page = 0, int size = 20}) async => [
        Report(
          id: 'r1',
          code: 'TAR-2026-004821',
          title: 'Lundo la taka halijaondolewa kwa wiki mbili',
          status: 'IN_PROGRESS',
          categoryName: 'Usafi wa mazingira',
          createdAt: _ago(const Duration(days: 3)),
          dueAt: _ago(const Duration(days: -1)),
        ),
        Report(
          id: 'r2',
          code: 'TAR-2026-004655',
          title: 'Shimo kubwa barabarani linahatarisha pikipiki',
          status: 'RESOLVED',
          categoryName: 'Barabara na miundombinu',
          createdAt: _ago(const Duration(days: 9)),
          resolution: 'Shimo limezibwa na kandarasi wa halmashauri.',
        ),
        Report(
          id: 'r3',
          code: 'TAR-2026-004510',
          title: 'Bomba la maji limepasuka karibu na shule',
          status: 'NEW',
          categoryName: 'Maji na usafi',
          createdAt: _ago(const Duration(hours: 5)),
        ),
      ];

  @override
  Future<List<OutboxEntry>> pendingDrafts() async => const [];

  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

class _FakeNotificationRepository implements NotificationRepository {
  @override
  Future<List<AppNotification>> listMine() async => [
        AppNotification(
          id: 'n1',
          type: 'REPORT_STATUS',
          channel: 'PUSH',
          status: 'DELIVERED',
          payloadRef: 'TAR-2026-004821',
          createdAt: _ago(const Duration(hours: 1)),
        ),
        AppNotification(
          id: 'n2',
          type: 'ANNOUNCEMENT',
          channel: 'FEED',
          status: 'READ',
          createdAt: _ago(const Duration(hours: 8)),
          readAt: _ago(const Duration(hours: 7)),
        ),
        AppNotification(
          id: 'n3',
          type: 'REPORT_STATUS',
          channel: 'SMS',
          status: 'SENT',
          payloadRef: 'TAR-2026-004655',
          createdAt: _ago(const Duration(days: 2)),
        ),
      ];

  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

class _FakeProfileRepository implements ProfileRepository {
  @override
  Future<Me> getMe() async => const Me(
        userPublicId: 'u-001',
        tier: 'T2',
        phone: '+255712345678',
        firstName: 'Amina',
        lastName: 'Juma',
        phoneVerified: true,
        idVerified: false,
      );

  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

class _FakeRepRepository implements RepresentativeRepository {
  @override
  Future<MyRepresentatives> findByWard(String wardId) async =>
      const MyRepresentatives(
        wardName: 'Kata ya Msasani',
        constituencyName: 'Jimbo la Kawe',
        mp: RepresentativeSummary(
          id: 'mp1',
          type: 'MP',
          status: 'SITTING',
          partyName: 'Mbunge wa Jimbo',
          constituencyName: 'Jimbo la Kawe',
        ),
        councillors: [
          RepresentativeSummary(
            id: 'c1',
            type: 'COUNCILLOR',
            status: 'SITTING',
            partyName: 'Diwani wa Kata',
            wardName: 'Kata ya Msasani',
          ),
        ],
        wardExecutives: [
          RepresentativeSummary(
            id: 'we1',
            type: 'WARD_EXEC',
            status: 'SITTING',
            partyName: 'Mtendaji wa Kata',
            wardName: 'Kata ya Msasani',
          ),
        ],
      );

  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

class _FakeGeographyRepository implements GeographyRepository {
  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

class _OtpAuthRepository implements AuthRepository {
  @override
  Future<bool> hasSession() async => false;
  @override
  Future<bool> refreshSession() async => false;
  @override
  Future<OtpChallenge> requestSignupOtp(String phone) async =>
      const OtpChallenge(challengeId: 'chal-1');
  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}

/// Reports a live session so the global AuthBloc settles on AuthStatus
/// .authenticated — the feed (a T1 capability) then renders instead of the
/// Guest sign-in prompt.
class _AuthedAuthRepository implements AuthRepository {
  @override
  Future<bool> hasSession() async => true;
  @override
  Future<bool> refreshSession() async => true;
  @override
  noSuchMethod(Invocation i) => super.noSuchMethod(i);
}
