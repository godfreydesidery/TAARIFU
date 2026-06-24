/// Full-app smoke test: pumps the real onboarding widget tree exactly as
/// `app.dart` wires it (root `MultiBlocProvider` → `MaterialApp` with the
/// Swahili-first localization delegates → `OnboardingScreen`) and asserts the
/// FIRST screen renders without throwing.
///
/// WHY this shape (not `app.main()` verbatim): `main()` builds the production
/// [AppDependencies], which awaits `path_provider` and `flutter_secure_storage`
/// — platform plugins that are unavailable in a plain test host and would crash
/// before the first frame. Instead we reconstruct the SAME root tree the app
/// runs, but seed it with a plugin-free fake [AuthRepository] (no session →
/// `unauthenticated` → onboarding) and an [InMemorySettingsStore]. That still
/// exercises the real [AuthBloc] state machine, the real [SettingsCubit], the
/// real localization, and the real [OnboardingScreen] — the genuine "does the
/// app boot to its first screen" check (CLAUDE.md §10, mirrors the patterns in
/// test/auth_bloc_test.dart and test/widget_test.dart).
///
/// This is registered as an integration test (uses
/// `IntegrationTestWidgetsFlutterBinding`) so it can also be promoted to run on a
/// device/emulator later (`flutter test integration_test/`), unchanged.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:taarifu_citizen/core/settings/app_settings.dart';
import 'package:taarifu_citizen/core/settings/settings_cubit.dart';
import 'package:taarifu_citizen/core/settings/settings_store.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_bloc.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_event.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_state.dart';
import 'package:taarifu_citizen/features/auth/data/auth_models.dart';
import 'package:taarifu_citizen/features/auth/data/auth_repository.dart';
import 'package:taarifu_citizen/features/auth/view/onboarding_screen.dart';
import 'package:taarifu_citizen/l10n/app_localizations.dart';

/// A plugin-free [AuthRepository] that reports NO session, so [AuthBloc] settles
/// on [AuthStatus.unauthenticated] and the root routes to [OnboardingScreen].
/// Every other method is a benign stub — the smoke test never invokes them.
class _NoSessionAuthRepository implements AuthRepository {
  @override
  Future<bool> hasSession() async => false;

  @override
  Future<bool> refreshSession() async => false;

  @override
  Future<void> logout() async {}

  @override
  Future<OtpChallenge> requestSignupOtp(String phone) async =>
      const OtpChallenge(challengeId: 'chal');

  @override
  Future<AuthResult> completeSignup({
    required String challengeId,
    required String code,
  }) async => const AuthResult(
    userPublicId: 'u',
    tier: 'T1',
    tokens: TokenPair(accessToken: 'a', refreshToken: 'r'),
  );

  @override
  Future<OtpChallenge> requestLoginOtp(String phone) async =>
      const OtpChallenge(challengeId: 'chal');

  @override
  Future<LoginResult> loginWithOtp({
    required String challengeId,
    required String code,
  }) async => const LoginResult(mfaRequired: false);

  @override
  Future<LoginResult> loginWithPassword({
    required String accountKey,
    required String password,
  }) async => const LoginResult(mfaRequired: false);
}

/// Builds the same root tree as `TaarifuApp`, but over the test seams.
Widget _bootableApp() {
  final settingsStore = InMemorySettingsStore();
  final authRepository = _NoSessionAuthRepository();
  return MultiBlocProvider(
    providers: [
      BlocProvider<SettingsCubit>(
        create: (_) => SettingsCubit(
          store: settingsStore,
          initial: AppSettings.defaults,
        ),
      ),
      BlocProvider<AuthBloc>(
        create: (_) =>
            AuthBloc(repository: authRepository)..add(const AuthStarted()),
      ),
    ],
    child: BlocBuilder<SettingsCubit, AppSettings>(
      buildWhen: (p, c) => p.languageCode != c.languageCode,
      builder: (context, settings) => MaterialApp(
        onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
        locale: Locale(settings.languageCode),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: BlocBuilder<AuthBloc, AuthState>(
          buildWhen: (prev, curr) => prev.status != curr.status,
          builder: (context, state) {
            switch (state.status) {
              case AuthStatus.unknown:
                return const Scaffold(
                  body: Center(child: CircularProgressIndicator()),
                );
              case AuthStatus.unauthenticated:
              case AuthStatus.otpSent:
                return const OnboardingScreen();
              case AuthStatus.authenticated:
                // Unreachable in this no-session smoke test.
                return const Scaffold(body: SizedBox.shrink());
            }
          },
        ),
      ),
    ),
  );
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('App smoke', () {
    testWidgets(
      'boots to the OnboardingScreen and renders Swahili-first welcome copy',
      (tester) async {
        await tester.pumpWidget(_bootableApp());
        // Settle the AuthStarted async transition (unknown → unauthenticated)
        // and the first frame of the onboarding screen.
        await tester.pumpAndSettle();

        // The first screen is the onboarding screen — and it rendered without
        // throwing (a thrown build error would have failed pumpAndSettle).
        expect(find.byType(OnboardingScreen), findsOneWidget);

        // Swahili-first copy is present (PRD §14): the welcome heading and the
        // "request OTP" call-to-action, proving localization wired up too.
        final l10n = AppLocalizations.of(
          tester.element(find.byType(OnboardingScreen)),
        );
        expect(find.text(l10n.onboardingWelcomeTitle), findsOneWidget);
        expect(find.text(l10n.requestOtpButton), findsOneWidget);

        // A phone input is present (the first onboarding step).
        expect(find.byType(TextFormField), findsOneWidget);
      },
    );
  });
}
