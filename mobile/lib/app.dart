/// The root application widget: wires localization (Swahili default, English
/// secondary, switchable at runtime via [SettingsCubit]), the theme, the
/// app-wide [AuthBloc], and routes between onboarding and the signed-in shell
/// based on [AuthStatus].
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/di/app_dependencies.dart';
import 'core/settings/app_settings.dart';
import 'core/settings/settings_cubit.dart';
import 'core/theme/app_theme.dart';
import 'features/auth/bloc/auth_bloc.dart';
import 'features/auth/bloc/auth_event.dart';
import 'features/auth/bloc/auth_state.dart';
import 'features/auth/view/onboarding_screen.dart';
import 'features/shell/home_shell.dart';
import 'l10n/app_localizations.dart';

/// The Taarifu citizen app.
class TaarifuApp extends StatelessWidget {
  /// Creates the app over its [dependencies].
  const TaarifuApp({required this.dependencies, super.key});

  /// The composition root.
  final AppDependencies dependencies;

  @override
  Widget build(BuildContext context) {
    return MultiBlocProvider(
      providers: [
        // The settings cubit sits above MaterialApp so a language change rebuilds
        // the whole app's locale immediately (and persists across restarts).
        BlocProvider<SettingsCubit>(
          create: (_) => SettingsCubit(
            store: dependencies.settingsStore,
            initial: dependencies.settingsInitial,
          ),
        ),
        BlocProvider<AuthBloc>(
          create: (_) =>
              AuthBloc(repository: dependencies.authRepository)
                ..add(const AuthStarted()),
        ),
      ],
      child: BlocBuilder<SettingsCubit, AppSettings>(
        buildWhen: (p, c) =>
            p.languageCode != c.languageCode || p.themeMode != c.themeMode,
        builder: (context, settings) => MaterialApp(
          onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
          theme: AppTheme.light(),
          darkTheme: AppTheme.dark(),
          // Honour the citizen's theme choice (Settings); defaults to following
          // the device (system) so night mode just works.
          themeMode: settings.themeMode,
          // Swahili first (PRD §14, ADR-0010); the citizen may switch to English
          // in Settings — the chosen locale drives the whole app here.
          locale: Locale(settings.languageCode),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: const [
            AppLocalizations.delegate,
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          home: _Root(dependencies: dependencies),
        ),
      ),
    );
  }
}

/// Routes on [AuthStatus]: splash while unknown, onboarding when unauthenticated
/// or mid-OTP, the shell when authenticated.
class _Root extends StatelessWidget {
  const _Root({required this.dependencies});

  final AppDependencies dependencies;

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<AuthBloc, AuthState>(
      buildWhen: (prev, curr) => prev.status != curr.status,
      builder: (context, state) {
        switch (state.status) {
          case AuthStatus.unknown:
            return const _SplashScreen();
          case AuthStatus.unauthenticated:
          case AuthStatus.otpSent:
            return const OnboardingScreen();
          case AuthStatus.authenticated:
            return HomeShell(dependencies: dependencies);
        }
      },
    );
  }
}

/// A branded boot splash shown while the session is resolved (`AuthStatus
/// .unknown`). A soft civic-green gradient with the app mark and a quiet
/// progress indicator — far more polished than a bare centred spinner, and it
/// holds for only the brief session check.
class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [scheme.primary, scheme.primaryContainer],
          ),
        ),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 96,
                height: 96,
                decoration: BoxDecoration(
                  color: scheme.onPrimary.withValues(alpha: 0.15),
                  shape: BoxShape.circle,
                ),
                child: Icon(
                  Icons.campaign_rounded,
                  size: 52,
                  color: scheme.onPrimary,
                ),
              ),
              const SizedBox(height: 20),
              Text(
                AppLocalizations.of(context).appTitle,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  color: scheme.onPrimary,
                  letterSpacing: 1,
                ),
              ),
              const SizedBox(height: 28),
              SizedBox(
                width: 28,
                height: 28,
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: scheme.onPrimary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
