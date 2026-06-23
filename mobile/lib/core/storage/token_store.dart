/// Secure persistence for the access + refresh token pair.
///
/// WHY flutter_secure_storage (Android Keystore-backed): auth tokens are
/// sensitive credentials and must never sit in plaintext `SharedPreferences` or
/// an unencrypted cache (PRD §18, ARCHITECTURE §6). On logout we *wipe* the
/// store (secure wipe), and on a detected refresh-token reuse the backend
/// revokes the family — the client simply clears local tokens and re-onboards.
library;

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Persists and retrieves the JWT pair from OS-backed secure storage.
class TokenStore {
  /// Creates the store. [storage] is injectable so tests can use an in-memory
  /// fake instead of touching the device keystore.
  TokenStore({FlutterSecureStorage? storage})
    : _storage =
          storage ??
          const FlutterSecureStorage(
            // Encrypted-SharedPreferences on Android keeps keys in the Keystore.
            aOptions: AndroidOptions(encryptedSharedPreferences: true),
          );

  final FlutterSecureStorage _storage;

  static const String _accessKey = 'taarifu.access_token';
  static const String _refreshKey = 'taarifu.refresh_token';

  /// Reads the stored access token, or `null` if none.
  Future<String?> readAccessToken() => _storage.read(key: _accessKey);

  /// Reads the stored refresh token, or `null` if none.
  Future<String?> readRefreshToken() => _storage.read(key: _refreshKey);

  /// Persists a freshly issued token [pair].
  Future<void> save({
    required String accessToken,
    required String refreshToken,
  }) async {
    await _storage.write(key: _accessKey, value: accessToken);
    await _storage.write(key: _refreshKey, value: refreshToken);
  }

  /// Securely wipes both tokens (logout, or refresh-family revocation).
  Future<void> clear() async {
    await _storage.delete(key: _accessKey);
    await _storage.delete(key: _refreshKey);
  }

  /// Whether a session looks present (an access token is stored).
  Future<bool> get hasSession async => (await readAccessToken()) != null;
}
