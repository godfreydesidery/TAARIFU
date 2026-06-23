/// bloc_test coverage for the onboarding state machine, using a fake repository
/// so no network/secure-storage is touched (CLAUDE.md §10).
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/core/network/connectivity_service.dart';
import 'package:taarifu_citizen/core/storage/token_store.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_bloc.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_event.dart';
import 'package:taarifu_citizen/features/auth/bloc/auth_state.dart';
import 'package:taarifu_citizen/features/auth/data/auth_models.dart';
import 'package:taarifu_citizen/features/auth/data/auth_repository.dart';

/// A fake [AuthRepository] that records calls and returns canned results,
/// avoiding the real [ApiClient]/[TokenStore]/[ConnectivityService] graph.
class _FakeAuthRepository implements AuthRepository {
  bool sessionPresent = false;
  String? lastChallengeId;

  @override
  Future<OtpChallenge> requestSignupOtp(String phone) async =>
      const OtpChallenge(challengeId: 'chal-1');

  @override
  Future<AuthResult> completeSignup({
    required String challengeId,
    required String code,
  }) async {
    lastChallengeId = challengeId;
    return const AuthResult(
      userPublicId: 'user-1',
      tier: 'T1',
      tokens: TokenPair(accessToken: 'a', refreshToken: 'r'),
    );
  }

  @override
  Future<bool> hasSession() async => sessionPresent;

  @override
  Future<bool> refreshSession() async => true;

  @override
  Future<void> logout() async {}

  // Unused by these tests.
  @override
  Future<OtpChallenge> requestLoginOtp(String phone) async =>
      const OtpChallenge(challengeId: 'login-chal');

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

void main() {
  group('AuthBloc', () {
    late _FakeAuthRepository repo;

    setUp(() => repo = _FakeAuthRepository());

    blocTest<AuthBloc, AuthState>(
      'AuthStarted with no session → unauthenticated',
      build: () => AuthBloc(repository: repo),
      act: (bloc) => bloc.add(const AuthStarted()),
      expect: () => [
        isA<AuthState>().having(
          (s) => s.status,
          'status',
          AuthStatus.unauthenticated,
        ),
      ],
    );

    blocTest<AuthBloc, AuthState>(
      'AuthStarted with a session → refresh → authenticated',
      build: () => AuthBloc(repository: repo..sessionPresent = true),
      act: (bloc) => bloc.add(const AuthStarted()),
      expect: () => [
        isA<AuthState>().having(
          (s) => s.status,
          'status',
          AuthStatus.authenticated,
        ),
      ],
    );

    blocTest<AuthBloc, AuthState>(
      'OTP request → otpSent with the challenge id',
      build: () => AuthBloc(repository: repo),
      act: (bloc) => bloc.add(const AuthOtpRequested('+255712345678')),
      expect: () => [
        isA<AuthState>().having((s) => s.submitting, 'submitting', true),
        isA<AuthState>()
            .having((s) => s.status, 'status', AuthStatus.otpSent)
            .having((s) => s.challengeId, 'challengeId', 'chal-1')
            .having((s) => s.submitting, 'submitting', false),
      ],
    );

    blocTest<AuthBloc, AuthState>(
      'OTP verify after request → authenticated at T1',
      build: () => AuthBloc(repository: repo),
      act: (bloc) async {
        bloc.add(const AuthOtpRequested('+255712345678'));
        await Future<void>.delayed(const Duration(milliseconds: 10));
        bloc.add(const AuthOtpVerified('123456'));
      },
      skip: 2, // skip the two OTP-request states asserted above
      expect: () => [
        isA<AuthState>().having((s) => s.submitting, 'submitting', true),
        isA<AuthState>()
            .having((s) => s.status, 'status', AuthStatus.authenticated)
            .having((s) => s.tier, 'tier', 'T1'),
      ],
    );
  });
}
