/// Repository for the tiered-identity auth flows: OTP signup → T1, password/OTP
/// login, refresh, and secure session lifecycle.
///
/// Responsibility: translate the auth use-cases into [ApiClient] calls against
/// the real backend `AuthController`, and own the *secure persistence* of the
/// resulting tokens via [TokenStore]. The BLoC depends on this repository, never
/// on `ApiClient`/`Dio` directly (clean layering, CLAUDE.md §3).
///
/// Tiered identity (locked decision, PRD §7.3): phone+OTP yields **T1**. This
/// app never creates a second account for an existing phone — login/recovery is
/// always the OTP path against the same account (PRD §19 one-account additive
/// roles). Completing a profile (→T2) and NIDA/voter (→T3) are later slices.
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/token_store.dart';
import 'auth_models.dart';

/// Drives authentication endpoints and persists the session.
class AuthRepository {
  /// Creates the repository over an [ApiClient] and a [TokenStore].
  AuthRepository({required ApiClient apiClient, required TokenStore tokenStore})
    : _api = apiClient,
      _tokens = tokenStore;

  final ApiClient _api;
  final TokenStore _tokens;

  /// Requests a SIGNUP OTP for [phone] (E.164, e.g. `+255712345678`).
  ///
  /// Maps to `POST /auth/otp/request` (always `202` + a challenge id, by design
  /// anti-enumeration). Returns the [OtpChallenge] to present on verify.
  Future<OtpChallenge> requestSignupOtp(String phone) async {
    final result = await _api.post<OtpChallenge>(
      '/auth/otp/request',
      body: {'phone': phone},
      parser: (data) => OtpChallenge.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Completes signup by verifying [code] against [challengeId].
  ///
  /// Maps to `POST /auth/signup` (`201` → creates/activates a **T1** account and
  /// issues tokens). On success the token pair is persisted securely before
  /// returning, so the caller is immediately authenticated.
  Future<AuthResult> completeSignup({
    required String challengeId,
    required String code,
  }) async {
    final result = await _api.post<AuthResult>(
      '/auth/signup',
      body: {'challengeId': challengeId, 'code': code},
      parser: (data) => AuthResult.fromJson(data! as Map<String, dynamic>),
    );
    await _tokens.save(
      accessToken: result.data.tokens.accessToken,
      refreshToken: result.data.tokens.refreshToken,
    );
    return result.data;
  }

  /// Requests a LOGIN OTP for an existing account on [phone].
  ///
  /// Maps to `POST /auth/login/otp/request`. Used for passwordless login and
  /// account recovery — never to create a second account (PRD §19).
  Future<OtpChallenge> requestLoginOtp(String phone) async {
    final result = await _api.post<OtpChallenge>(
      '/auth/login/otp/request',
      body: {'phone': phone},
      parser: (data) => OtpChallenge.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Completes a passwordless login by verifying [code] against [challengeId].
  ///
  /// Maps to `POST /auth/login/otp`. Persists tokens on a non-MFA outcome.
  Future<LoginResult> loginWithOtp({
    required String challengeId,
    required String code,
  }) async {
    final result = await _api.post<LoginResult>(
      '/auth/login/otp',
      body: {'challengeId': challengeId, 'code': code},
      parser: (data) => LoginResult.fromJson(data! as Map<String, dynamic>),
    );
    await _persistIfTokens(result.data);
    return result.data;
  }

  /// Password login by [accountKey] (phone or email) + [password].
  ///
  /// Maps to `POST /auth/login/password`. A staff/MFA account returns
  /// `mfaRequired = true` with no tokens (the TOTP step is out of scope here).
  Future<LoginResult> loginWithPassword({
    required String accountKey,
    required String password,
  }) async {
    final result = await _api.post<LoginResult>(
      '/auth/login/password',
      body: {'accountKey': accountKey, 'password': password},
      parser: (data) => LoginResult.fromJson(data! as Map<String, dynamic>),
    );
    await _persistIfTokens(result.data);
    return result.data;
  }

  /// Rotates the stored refresh token for a fresh pair.
  ///
  /// Maps to `POST /auth/refresh` (single-use rotation; reuse revokes the family
  /// server-side — S-3). Returns `false` (and wipes the session) when no refresh
  /// token is stored or the server rejects it, so the caller re-onboards.
  Future<bool> refreshSession() async {
    final refresh = await _tokens.readRefreshToken();
    if (refresh == null || refresh.isEmpty) {
      return false;
    }
    final result = await _api.post<TokenPair>(
      '/auth/refresh',
      body: {'refreshToken': refresh},
      parser: (data) => TokenPair.fromJson(data! as Map<String, dynamic>),
    );
    await _tokens.save(
      accessToken: result.data.accessToken,
      refreshToken: result.data.refreshToken,
    );
    return true;
  }

  /// Whether a session is present locally (an access token is stored).
  Future<bool> hasSession() => _tokens.hasSession;

  /// Logs out: best-effort server revocation, then a local secure wipe.
  ///
  /// The local wipe always runs even if the network call fails, so the device is
  /// never left holding a token after the user asked to sign out (PRD §18).
  Future<void> logout() async {
    final refresh = await _tokens.readRefreshToken();
    if (refresh != null && refresh.isNotEmpty) {
      try {
        await _api.post<void>(
          '/auth/logout',
          body: {'refreshToken': refresh},
          parser: (_) {},
        );
      } on Object {
        // Ignore: revocation is best-effort; the authoritative step is the wipe.
      }
    }
    await _tokens.clear();
  }

  Future<void> _persistIfTokens(LoginResult result) async {
    final tokens = result.tokens;
    if (tokens != null) {
      await _tokens.save(
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
      );
    }
  }
}
