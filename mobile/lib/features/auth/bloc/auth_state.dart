/// State for [AuthBloc].
///
/// Models the onboarding/session machine as one immutable value with a [status]
/// enum, so the UI does a single switch:
///
/// ```
/// unknown ──Started──▶ unauthenticated ──OtpRequested──▶ otpSent
///    │                        ▲                              │
///    └── refresh ok ──▶ authenticated ◀── OtpVerified ───────┘
///                              │  ▲                           │
///                       LoggedOut │                      (failure)
///                              ▼  └──────────────────────────┘
///                       unauthenticated
/// ```
library;

import 'package:equatable/equatable.dart';

/// High-level auth status the UI routes on.
enum AuthStatus {
  /// Startup check not yet complete (show a splash/loader).
  unknown,

  /// No valid session — show onboarding (phone entry).
  unauthenticated,

  /// An OTP was requested and sent — show the code-entry step.
  otpSent,

  /// A signed-in citizen (at least T1).
  authenticated,
}

/// Immutable auth state.
class AuthState extends Equatable {
  /// Creates a state. Prefer the named constructors.
  const AuthState({
    required this.status,
    this.phone,
    this.challengeId,
    this.tier,
    this.submitting = false,
    this.errorKey,
  });

  /// The initial unknown state (startup).
  const AuthState.unknown() : this(status: AuthStatus.unknown);

  /// The status the UI routes on.
  final AuthStatus status;

  /// The phone under onboarding (carried into the OTP step).
  final String? phone;

  /// The active OTP challenge id, or `null`.
  final String? challengeId;

  /// The trust tier once authenticated (e.g. `T1`), or `null`.
  final String? tier;

  /// Whether an async action is in flight (drives button spinners/disabling).
  final bool submitting;

  /// A transient error to surface, as a caught object the UI localises via
  /// `FailureMessages`; `null` when there is no error.
  final Object? errorKey;

  /// Returns a copy with the given overrides. [clearError] drops any error.
  AuthState copyWith({
    AuthStatus? status,
    String? phone,
    String? challengeId,
    String? tier,
    bool? submitting,
    Object? errorKey,
    bool clearError = false,
  }) {
    return AuthState(
      status: status ?? this.status,
      phone: phone ?? this.phone,
      challengeId: challengeId ?? this.challengeId,
      tier: tier ?? this.tier,
      submitting: submitting ?? this.submitting,
      errorKey: clearError ? null : (errorKey ?? this.errorKey),
    );
  }

  @override
  List<Object?> get props => [
    status,
    phone,
    challengeId,
    tier,
    submitting,
    errorKey,
  ];
}
