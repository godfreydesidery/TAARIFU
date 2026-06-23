/// Wire models for the identity/auth endpoints, mirroring the backend DTOs in
/// `com.taarifu.identity.api.dto` exactly (AuthController).
///
/// Kept hand-written (no codegen) to keep the foundation lean; each parses the
/// `data` node the [ApiClient] hands it.
library;

/// The token pair returned by signup/login/refresh (backend `TokenPairDto`).
class TokenPair {
  /// Creates a pair.
  const TokenPair({required this.accessToken, required this.refreshToken});

  /// Short-lived JWT access token.
  final String accessToken;

  /// Rotating, single-use refresh token.
  final String refreshToken;

  /// Parses a `{ accessToken, refreshToken }` node.
  factory TokenPair.fromJson(Map<String, dynamic> json) => TokenPair(
    accessToken: json['accessToken'] as String,
    refreshToken: json['refreshToken'] as String,
  );
}

/// An OTP challenge id, returned by `/auth/otp/request` and the login-OTP
/// request (backend `OtpChallengeDto`). The client echoes it back on verify.
class OtpChallenge {
  /// Creates a challenge holder.
  const OtpChallenge({required this.challengeId});

  /// The opaque challenge id (UUID string) to present on verify.
  final String challengeId;

  /// Parses a `{ challengeId }` node.
  factory OtpChallenge.fromJson(Map<String, dynamic> json) =>
      OtpChallenge(challengeId: json['challengeId'] as String);
}

/// The result of completing signup (backend `AuthResultDto`): the new account's
/// public id, its trust [tier] (e.g. `T1`), and the issued [tokens].
class AuthResult {
  /// Creates a signup result.
  const AuthResult({
    required this.userPublicId,
    required this.tier,
    required this.tokens,
  });

  /// The account's public id (UUID string).
  final String userPublicId;

  /// The trust tier reached (e.g. `T1` after phone verification).
  final String tier;

  /// The freshly issued token pair.
  final TokenPair tokens;

  /// Parses a `{ userPublicId, tier, tokens }` node.
  factory AuthResult.fromJson(Map<String, dynamic> json) => AuthResult(
    userPublicId: json['userPublicId'] as String,
    tier: json['tier'] as String,
    tokens: TokenPair.fromJson(json['tokens'] as Map<String, dynamic>),
  );
}

/// The first-factor login outcome (backend `LoginResultDto`).
///
/// For a normal citizen [tokens] is populated and [mfaRequired] is false. For a
/// staff/MFA account [mfaRequired] is true, [tokens] is `null`, and [mfaToken]
/// must be completed at `/auth/login/totp` — the citizen app does not yet drive
/// that staff path, but the shape is modelled so it is not silently dropped.
class LoginResult {
  /// Creates a login outcome.
  const LoginResult({required this.mfaRequired, this.tokens, this.mfaToken});

  /// Whether a TOTP second factor is required (staff accounts).
  final bool mfaRequired;

  /// The issued pair, or `null` when [mfaRequired].
  final TokenPair? tokens;

  /// The MFA challenge token, or `null` when no MFA is needed.
  final String? mfaToken;

  /// Parses a `{ mfaRequired, tokens?, mfaToken? }` node.
  factory LoginResult.fromJson(Map<String, dynamic> json) => LoginResult(
    mfaRequired: json['mfaRequired'] == true,
    tokens: json['tokens'] == null
        ? null
        : TokenPair.fromJson(json['tokens'] as Map<String, dynamic>),
    mfaToken: json['mfaToken'] as String?,
  );
}
