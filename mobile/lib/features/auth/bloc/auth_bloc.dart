/// The auth BLoC: drives the tiered-identity onboarding (phone → OTP → T1) and
/// the cross-cold-start session check, over [AuthRepository].
///
/// Responsibility: orchestrate auth use-cases and expose a single [AuthState]
/// the app routes on. It holds no transport details and no widgets — pure logic,
/// unit-testable with `bloc_test` (CLAUDE.md §10).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/auth_repository.dart';
import 'auth_event.dart';
import 'auth_state.dart';

/// Manages authentication state for the whole app.
class AuthBloc extends Bloc<AuthEvent, AuthState> {
  /// Creates the BLoC over an [AuthRepository] and registers handlers.
  AuthBloc({required AuthRepository repository})
    : _repository = repository,
      super(const AuthState.unknown()) {
    on<AuthStarted>(_onStarted);
    on<AuthOtpRequested>(_onOtpRequested);
    on<AuthOtpVerified>(_onOtpVerified);
    on<AuthPhoneEditRequested>(_onPhoneEdit);
    on<AuthLoggedOut>(_onLoggedOut);
  }

  final AuthRepository _repository;

  /// Startup: if a session exists, try to refresh it; otherwise onboard.
  ///
  /// A failed/absent refresh degrades cleanly to [AuthStatus.unauthenticated]
  /// rather than crashing — including when offline (the citizen can still browse
  /// public surfaces like find-my-rep without a session).
  Future<void> _onStarted(AuthStarted event, Emitter<AuthState> emit) async {
    if (!await _repository.hasSession()) {
      emit(const AuthState(status: AuthStatus.unauthenticated));
      return;
    }
    try {
      final ok = await _repository.refreshSession();
      emit(
        AuthState(
          status: ok
              ? AuthStatus.authenticated
              : AuthStatus.unauthenticated,
        ),
      );
    } on Object {
      // Offline or transient: keep the stored session optimistically; the next
      // authenticated call will re-trigger refresh/onboarding as needed.
      emit(const AuthState(status: AuthStatus.authenticated));
    }
  }

  /// Requests a signup OTP and advances to the code-entry step.
  Future<void> _onOtpRequested(
    AuthOtpRequested event,
    Emitter<AuthState> emit,
  ) async {
    emit(
      state.copyWith(
        submitting: true,
        phone: event.phone,
        clearError: true,
      ),
    );
    try {
      final challenge = await _repository.requestSignupOtp(event.phone);
      emit(
        state.copyWith(
          status: AuthStatus.otpSent,
          phone: event.phone,
          challengeId: challenge.challengeId,
          submitting: false,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(submitting: false, errorKey: e));
    }
  }

  /// Verifies the OTP to complete T1 signup and authenticates.
  Future<void> _onOtpVerified(
    AuthOtpVerified event,
    Emitter<AuthState> emit,
  ) async {
    final challengeId = state.challengeId;
    if (challengeId == null) {
      emit(state.copyWith(status: AuthStatus.unauthenticated));
      return;
    }
    emit(state.copyWith(submitting: true, clearError: true));
    try {
      final result = await _repository.completeSignup(
        challengeId: challengeId,
        code: event.code,
      );
      emit(
        AuthState(
          status: AuthStatus.authenticated,
          tier: result.tier,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(submitting: false, errorKey: e));
    }
  }

  /// Goes back to phone entry (e.g. wrong number).
  void _onPhoneEdit(AuthPhoneEditRequested event, Emitter<AuthState> emit) {
    emit(
      AuthState(status: AuthStatus.unauthenticated, phone: state.phone),
    );
  }

  /// Signs out and returns to onboarding.
  Future<void> _onLoggedOut(
    AuthLoggedOut event,
    Emitter<AuthState> emit,
  ) async {
    await _repository.logout();
    emit(const AuthState(status: AuthStatus.unauthenticated));
  }
}
