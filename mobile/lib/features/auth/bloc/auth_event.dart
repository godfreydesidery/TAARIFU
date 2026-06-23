/// Events for [AuthBloc] — the tiered-identity onboarding + session lifecycle.
library;

import 'package:equatable/equatable.dart';

/// Base type for all auth events.
sealed class AuthEvent extends Equatable {
  const AuthEvent();

  @override
  List<Object?> get props => const [];
}

/// Fired once at startup to check for an existing secure session and, if any,
/// refresh it (a valid refresh keeps the citizen signed in across cold starts).
class AuthStarted extends AuthEvent {
  /// Creates the startup event.
  const AuthStarted();
}

/// Requests a signup OTP for the given E.164 [phone].
class AuthOtpRequested extends AuthEvent {
  /// Creates the request with the destination [phone].
  const AuthOtpRequested(this.phone);

  /// The destination phone in E.164 (e.g. `+255712345678`).
  final String phone;

  @override
  List<Object?> get props => [phone];
}

/// Verifies the OTP [code] for the current challenge to complete T1 signup.
class AuthOtpVerified extends AuthEvent {
  /// Creates the verify event with the entered [code].
  const AuthOtpVerified(this.code);

  /// The one-time code the citizen received by SMS.
  final String code;

  @override
  List<Object?> get props => [code];
}

/// Returns from the OTP-entry step back to the phone-entry step.
class AuthPhoneEditRequested extends AuthEvent {
  /// Creates the edit-phone event.
  const AuthPhoneEditRequested();
}

/// Signs the citizen out (server revoke best-effort + local secure wipe).
class AuthLoggedOut extends AuthEvent {
  /// Creates the logout event.
  const AuthLoggedOut();
}
