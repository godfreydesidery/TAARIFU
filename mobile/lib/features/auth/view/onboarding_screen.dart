/// The tiered-identity onboarding screen: phone entry → OTP entry → T1.
///
/// Drives [AuthBloc]. Two visual steps switch on [AuthState.status]
/// (`unauthenticated` → phone, `otpSent` → code). All copy is localised; errors
/// are surfaced via [FailureMessages] (Swahili-first).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/theme/app_palette.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/auth_bloc.dart';
import '../bloc/auth_event.dart';
import '../bloc/auth_state.dart';

/// Stateful onboarding view owning the phone/OTP text controllers.
class OnboardingScreen extends StatefulWidget {
  /// Creates the screen.
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _otpController = TextEditingController();
  final GlobalKey<FormState> _formKey = GlobalKey<FormState>();

  @override
  void dispose() {
    _phoneController.dispose();
    _otpController.dispose();
    super.dispose();
  }

  /// Validates the phone as E.164 (`+` then 9–15 digits), matching the backend.
  String? _validatePhone(AppLocalizations l10n, String? value) {
    final v = (value ?? '').trim();
    if (!RegExp(r'^\+\d{9,15}$').hasMatch(v)) {
      return l10n.phoneInvalid;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      body: BlocConsumer<AuthBloc, AuthState>(
        listenWhen: (prev, curr) => curr.errorKey != prev.errorKey,
        listener: (context, state) {
          if (state.errorKey != null) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(
                  content: Text(FailureMessages.of(l10n, state.errorKey!)),
                ),
              );
          }
        },
        builder: (context, state) {
          final isOtpStep = state.status == AuthStatus.otpSent;
          return Column(
            children: [
              _BrandHero(l10n: l10n),
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(
                    AppPalette.spaceXl,
                    AppPalette.spaceXl,
                    AppPalette.spaceXl,
                    AppPalette.spaceXl,
                  ),
                  child: Form(
                    key: _formKey,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        if (!isOtpStep) ..._phoneStep(context, l10n, state),
                        if (isOtpStep) ..._otpStep(context, l10n, state),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  List<Widget> _phoneStep(
    BuildContext context,
    AppLocalizations l10n,
    AuthState state,
  ) {
    return [
      TextFormField(
        controller: _phoneController,
        keyboardType: TextInputType.phone,
        decoration: InputDecoration(
          labelText: l10n.phoneLabel,
          hintText: l10n.phoneHint,
        ),
        validator: (v) => _validatePhone(l10n, v),
      ),
      const SizedBox(height: 20),
      FilledButton(
        onPressed: state.submitting
            ? null
            : () {
                if (_formKey.currentState?.validate() ?? false) {
                  context.read<AuthBloc>().add(
                    AuthOtpRequested(_phoneController.text.trim()),
                  );
                }
              },
        child: state.submitting
            ? const _ButtonSpinner()
            : Text(l10n.requestOtpButton),
      ),
    ];
  }

  List<Widget> _otpStep(
    BuildContext context,
    AppLocalizations l10n,
    AuthState state,
  ) {
    return [
      Text(l10n.otpSentMessage),
      const SizedBox(height: 16),
      TextFormField(
        controller: _otpController,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(
          labelText: l10n.otpLabel,
          hintText: l10n.otpHint,
        ),
        validator: (v) =>
            (v == null || v.trim().length < 4) ? l10n.otpInvalid : null,
      ),
      const SizedBox(height: 20),
      FilledButton(
        onPressed: state.submitting
            ? null
            : () {
                if (_formKey.currentState?.validate() ?? false) {
                  context.read<AuthBloc>().add(
                    AuthOtpVerified(_otpController.text.trim()),
                  );
                }
              },
        child: state.submitting
            ? const _ButtonSpinner()
            : Text(l10n.verifyOtpButton),
      ),
      TextButton(
        onPressed: state.submitting
            ? null
            : () => context.read<AuthBloc>().add(
                const AuthPhoneEditRequested(),
              ),
        child: Text(l10n.changePhoneButton),
      ),
    ];
  }
}

/// A small inline spinner sized for a button's content slot.
class _ButtonSpinner extends StatelessWidget {
  const _ButtonSpinner();

  @override
  Widget build(BuildContext context) => const SizedBox(
    height: 20,
    width: 20,
    child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
  );
}

/// The branded onboarding hero: a soft civic-green gradient banner carrying the
/// app mark, the welcome title, and the plain-Swahili explainer. It anchors the
/// onboarding with a warm, trustworthy first impression while keeping the copy
/// (PRD §14 Swahili-first) front and centre.
class _BrandHero extends StatelessWidget {
  const _BrandHero({required this.l10n});

  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(
        AppPalette.spaceXl,
        56,
        AppPalette.spaceXl,
        AppPalette.spaceXl,
      ),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [scheme.primary, scheme.primaryContainer],
        ),
        borderRadius: const BorderRadius.vertical(
          bottom: Radius.circular(AppPalette.radiusSheet),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              color: scheme.onPrimary.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(AppPalette.radiusInput),
            ),
            child: Icon(
              Icons.campaign_rounded,
              size: 36,
              color: scheme.onPrimary,
            ),
          ),
          const SizedBox(height: AppPalette.spaceLg),
          Text(
            l10n.onboardingWelcomeTitle,
            style: Theme.of(context).textTheme.headlineSmall?.copyWith(
              color: scheme.onPrimary,
            ),
          ),
          const SizedBox(height: AppPalette.spaceSm),
          Text(
            l10n.onboardingWelcomeBody,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: scheme.onPrimary.withValues(alpha: 0.92),
            ),
          ),
        ],
      ),
    );
  }
}
