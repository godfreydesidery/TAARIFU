/// Headless companion to `integration_test/app_smoke_test.dart`.
///
/// WHY a twin under `test/`: the integration-test runner
/// (`IntegrationTestWidgetsFlutterBinding`) only targets a real device/emulator
/// — `flutter test integration_test/...` refuses to run on the VM host, the web
/// device, or an unconfigured desktop. With no emulator available in this
/// environment, this twin runs the IDENTICAL full-app boot smoke on the plain
/// Dart VM test host (`flutter test`), so the "does the app boot to its first
/// screen without throwing" check is actually executed and gates CI now. The
/// `integration_test/` file remains for on-device runs once an emulator exists.
///
/// It pumps the same root tree `app.dart` wires (root `MultiBlocProvider` →
/// `MaterialApp` with the Swahili-first localization delegates →
/// `OnboardingScreen`) over plugin-free seams (a no-session fake
/// [AuthRepository] and an [InMemorySettingsStore]), exercising the real
/// [AuthBloc], [SettingsCubit], localization, and [OnboardingScreen].
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

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

/// A plugin-free [AuthRepository] reporting NO session → onboarding.
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

/// Builds the same root tree as `TaarifuApp`, over the test seams.
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
                return const Scaffold(body: SizedBox.shrink());
            }
          },
        ),
      ),
    ),
  );
}

void main() {
  group('App smoke (headless)', () {
    testWidgets(
      'boots to the OnboardingScreen and renders Swahili-first welcome copy',
      (tester) async {
        await tester.pumpWidget(_bootableApp());
        await tester.pumpAndSettle();

        expect(find.byType(OnboardingScreen), findsOneWidget);

        final l10n = AppLocalizations.of(
          tester.element(find.byType(OnboardingScreen)),
        );
        expect(find.text(l10n.onboardingWelcomeTitle), findsOneWidget);
        expect(find.text(l10n.requestOtpButton), findsOneWidget);
        expect(find.byType(TextFormField), findsOneWidget);
      },
    );
  });
}
