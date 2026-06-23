/// The root application widget: wires localization (Swahili default, English
/// secondary), the theme, the app-wide [AuthBloc], and routes between
/// onboarding and the signed-in shell based on [AuthStatus].
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/di/app_dependencies.dart';
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
    return BlocProvider<AuthBloc>(
      create: (_) =>
          AuthBloc(repository: dependencies.authRepository)
            ..add(const AuthStarted()),
      child: MaterialApp(
        onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
        theme: AppTheme.light(),
        // Swahili first; English is the secondary/fallback (PRD §14, ADR-0010).
        locale: const Locale('sw'),
        supportedLocales: AppLocalizations.supportedLocales,
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: _Root(dependencies: dependencies),
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
            return const Scaffold(
              body: Center(child: CircularProgressIndicator()),
            );
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
