/// Phase-2 screenshot harness: renders the REDESIGNED elegant "social app"
/// screens with realistic Tanzanian civic content and captures PNGs via the
/// integration-test screenshot pipeline.
///
/// WHY this harness (not driving the live backend in a browser): the dev OTP is
/// delivered only over the SMS stub (read back in-process by integration tests,
/// never via a public endpoint), so a headless browser cannot get past OTP to
/// the authenticated social feed. This harness pumps the REAL screen widgets and
/// the REAL theme/localization, seeded with plugin-free fake repositories that
/// return canned data, so every captured frame is the genuine redesigned UI.
///
/// It is a screenshot/dev tool only (never asserts product behaviour) and adds no
/// app/APK weight — it lives under integration_test and uses test-only deps.
///
/// Run (web/Chrome, the channel used for these captures):
///   flutter drive --driver=test_driver/screenshot_driver.dart \
///     --target=integration_test/phase2_screenshots_test.dart -d chrome
library;

import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:taarifu_citizen/core/theme/app_theme.dart';
import 'package:taarifu_citizen/core/theme/app_palette.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_bloc.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_event.dart';
import 'package:taarifu_citizen/features/auth/data/auth_models.dart';
import 'package:taarifu_citizen/features/auth/data/auth_repository.dart';
import 'package:taarifu_citizen/features/auth/view/onboarding_screen.dart';

import 'package:taarifu_citizen/features/feed/bloc/feed_cubit.dart';
import 'package:taarifu_citizen/features/feed/data/feed_models.dart';
import 'package:taarifu_citizen/features/feed/data/feed_repository.dart';
import 'package:taarifu_citizen/features/feed/view/feed_screen.dart';

import 'package:taarifu_citizen/features/notifications/bloc/notifications_cubit.dart';
import 'package:taarifu_citizen/features/notifications/data/notification_models.dart';
import 'package:taarifu_citizen/features/notifications/data/notification_repository.dart';
import 'package:taarifu_citizen/features/notifications/view/notifications_screen.dart';

import 'package:taarifu_citizen/features/profile/bloc/profile_cubit.dart';
import 'package:taarifu_citizen/features/profile/data/profile_models.dart';
import 'package:taarifu_citizen/features/profile/data/profile_repository.dart';
import 'package:taarifu_citizen/features/profile/view/profile_screen.dart';

import 'package:taarifu_citizen/core/storage/outbox_entry.dart';
import 'package:taarifu_citizen/features/reporting/bloc/my_reports_cubit.dart';
import 'package:taarifu_citizen/features/reporting/data/report_repository.dart';
import 'package:taarifu_citizen/features/reporting/data/reporting_models.dart';
import 'package:taarifu_citizen/features/reporting/view/my_reports_screen.dart';

import 'package:taarifu_citizen/features/representatives/bloc/my_reps_cubit.dart';
import 'package:taarifu_citizen/features/representatives/data/representative_models.dart';
import 'package:taarifu_citizen/features/representatives/data/representative_repository.dart';
import 'package:taarifu_citizen/features/representatives/view/my_reps_screen.dart';

import 'package:taarifu_citizen/features/geography/data/geography_repository.dart';

import 'package:taarifu_citizen/l10n/app_localizations.dart';

// ---------------------------------------------------------------------------
// Fake repositories — plugin-free, return canned realistic Tanzanian content.
// Each only implements the method(s) its cubit calls; noSuchMethod covers the
// rest so we don't have to stub the whole surface.
// ---------------------------------------------------------------------------

DateTime _ago(Duration d) => DateTime.now().toUtc().subtract(d);

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
  Future<Announcement> getAnnouncement(String id) async => Announcement(
        id: id,
        title: 'Maji yatakatika kwa matengenezo Kata ya Kinondoni',
        bodySw: 'Maelezo kamili...',
      );

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

/// Auth repo that reports a live session so the OTP step / authed shell render.
class _OtpStepAuthRepository implements AuthRepository {
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

// ---------------------------------------------------------------------------
// A phone-frame wrapper: render each screen inside a MaterialApp with the REAL
// theme + Swahili-first localization, sized to a low-end phone (390x844).
// ---------------------------------------------------------------------------

Widget _framed({
  required Widget child,
  required ThemeMode mode,
  String locale = 'sw',
}) {
  return MaterialApp(
    debugShowCheckedModeBanner: false,
    onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
    theme: AppTheme.light(),
    darkTheme: AppTheme.dark(),
    themeMode: mode,
    locale: Locale(locale),
    supportedLocales: AppLocalizations.supportedLocales,
    localizationsDelegates: const [
      AppLocalizations.delegate,
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: child,
  );
}

/// The real signed-in social-app chrome (AppBar + IndexedStack body + docked
/// Report FAB + notched bottom bar), copied minimally from HomeShell so we can
/// drive it without the full plugin-backed AppDependencies. The bottom bar and
/// FAB are the actual redesign; the body hosts the real FeedScreen.
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
              onPressed: () {},
            ),
            IconButton(
              icon: const Icon(Icons.settings_outlined),
              onPressed: () {},
            ),
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
              _barItem(context, Icons.home_rounded, l10n.navHome, true),
              _barItem(context, Icons.account_balance_outlined, l10n.navMyReps,
                  false),
              const SizedBox(width: 56),
              _barItem(context, Icons.track_changes_outlined, l10n.navTrack,
                  false),
              _barItem(context, Icons.notifications_outlined,
                  l10n.navNotifications, false),
              _barItem(
                  context, Icons.person_outline_rounded, l10n.navProfile, false),
            ],
          ),
        ),
      ),
    );
  }

  Widget _barItem(
      BuildContext context, IconData icon, String label, bool selected) {
    final scheme = Theme.of(context).colorScheme;
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
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: color,
                    fontWeight:
                        selected ? FontWeight.w700 : FontWeight.w500,
                  )),
        ],
      ),
    );
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  // 390x844 logical @2x — a typical low-end Android (PRD §14).
  Future<void> sizePhone(WidgetTester tester) async {
    tester.view.physicalSize = const Size(780, 1688);
    tester.view.devicePixelRatio = 2.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
  }

  /// Pumps a bounded number of frames so a screen with a continuously running
  /// animation (e.g. the feed's staggered fade) cannot hang the capture the way
  /// an unbounded `pumpAndSettle` would on web.
  Future<void> settleBounded(WidgetTester tester) async {
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 60));
    }
  }

  Future<void> capture(WidgetTester tester, String name) async {
    // convertFlutterSurfaceToImage is the Android/IO path and HANGS on web; on
    // web takeScreenshot reads the canvas directly (the official integration_test
    // web pattern). So only convert off-web.
    if (!kIsWeb) {
      await binding.convertFlutterSurfaceToImage();
    }
    await settleBounded(tester);
    await binding.takeScreenshot(name);
  }

  group('Phase-2 elegant screens', () {
    testWidgets('03 OTP entry (Swahili)', (tester) async {
      await sizePhone(tester);
      final auth = AuthBloc(repository: _OtpStepAuthRepository());
      await tester.pumpWidget(
        BlocProvider<AuthBloc>.value(
          value: auth..add(const AuthStarted()),
          child: _framed(
            mode: ThemeMode.light,
            child: const OnboardingScreen(),
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 200));
      // Drive to the OTP step by dispatching the event directly (avoids the
      // brittle web enterText/IME path); the fake repo returns a challenge so
      // the bloc transitions unauthenticated -> otpSent and the code step shows.
      auth.add(const AuthOtpRequested('+255712345678'));
      await tester.pump(const Duration(milliseconds: 300));
      await capture(tester, '03-otp-entry-sw');
      await auth.close();
    });

    testWidgets('04 social feed home (Swahili, light)', (tester) async {
      await sizePhone(tester);
      await tester.pumpWidget(_framed(
        mode: ThemeMode.light,
        child: _SocialHome(feedRepo: _FakeFeedRepository()),
      ));
      await settleBounded(tester);
      await capture(tester, '04-social-feed-home-sw');
    });

    testWidgets('05a find-my-reps (Mbunge/Diwani)', (tester) async {
      await sizePhone(tester);
      final cubit = MyRepsCubit(repository: _FakeRepRepository());
      await tester.pumpWidget(_framed(
        mode: ThemeMode.light,
        child: Scaffold(
          appBar: AppBar(title: Builder(
            builder: (c) => Text(AppLocalizations.of(c).myRepsTitle),
          )),
          body: BlocProvider<MyRepsCubit>.value(
            value: cubit..loadForWard('ward-1'),
            child: MyRepsScreen(geographyRepository: _FakeGeographyRepository()),
          ),
        ),
      ));
      await settleBounded(tester);
      await capture(tester, '05a-find-my-reps-sw');
      await cubit.close();
    });

    testWidgets('05b track my reports', (tester) async {
      await sizePhone(tester);
      final cubit = MyReportsCubit(repository: _FakeReportRepository());
      await tester.pumpWidget(_framed(
        mode: ThemeMode.light,
        child: BlocProvider<MyReportsCubit>.value(
          value: cubit..load(),
          child: MyReportsScreen(onOpenReport: (_) {}),
        ),
      ));
      await settleBounded(tester);
      await capture(tester, '05b-track-reports-sw');
      await cubit.close();
    });

    testWidgets('05c notifications inbox', (tester) async {
      await sizePhone(tester);
      final cubit = NotificationsCubit(repository: _FakeNotificationRepository());
      await tester.pumpWidget(_framed(
        mode: ThemeMode.light,
        child: BlocProvider<NotificationsCubit>.value(
          value: cubit..load(),
          child: NotificationsScreen(onOpenPrefs: () {}),
        ),
      ));
      await settleBounded(tester);
      await capture(tester, '05c-notifications-sw');
      await cubit.close();
    });

    testWidgets('05d profile + verification', (tester) async {
      await sizePhone(tester);
      final cubit = ProfileCubit(repository: _FakeProfileRepository());
      await tester.pumpWidget(_framed(
        mode: ThemeMode.light,
        child: BlocProvider<ProfileCubit>.value(
          value: cubit..load(),
          child: ProfileScreen(geographyRepository: _FakeGeographyRepository()),
        ),
      ));
      await settleBounded(tester);
      await capture(tester, '05d-profile-sw');
      await cubit.close();
    });

    testWidgets('06 social feed home (DARK mode)', (tester) async {
      await sizePhone(tester);
      await tester.pumpWidget(_framed(
        mode: ThemeMode.dark,
        child: _SocialHome(feedRepo: _FakeFeedRepository()),
      ));
      await settleBounded(tester);
      await capture(tester, '06-social-feed-home-dark-sw');
    });

    testWidgets('02 onboarding phone (English variant)', (tester) async {
      await sizePhone(tester);
      final auth = AuthBloc(repository: _OtpStepAuthRepository());
      await tester.pumpWidget(
        BlocProvider<AuthBloc>.value(
          value: auth..add(const AuthStarted()),
          child: _framed(
            mode: ThemeMode.light,
            locale: 'en',
            child: const OnboardingScreen(),
          ),
        ),
      );
      await settleBounded(tester);
      await capture(tester, '02b-onboarding-phone-en');
      await auth.close();
    });
  });
}
