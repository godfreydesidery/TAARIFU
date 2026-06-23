/// A tiny client-side id generator for idempotency keys and local draft ids.
///
/// WHY a hand-rolled generator (no `uuid` package): the dependency budget is
/// tight — every package grows the APK a citizen on a small data bundle must
/// download (PRD §15). We only need a collision-resistant, opaque string for an
/// idempotency key / local draft id, not RFC-4122 strictness, so a v4-shaped
/// random string from `dart:math`'s [Random.secure] is sufficient and free.
///
/// The idempotency key is the backbone of offline-safe submit: a report drafted
/// offline carries the SAME key across every retry, so the backend de-duplicates
/// a replayed submit instead of creating a duplicate ticket (ARCHITECTURE §5.4,
/// PRD §17 "Idempotency keys for create/submit").
library;

import 'dart:math';

/// Generates opaque, collision-resistant client ids.
class IdGenerator {
  const IdGenerator._();

  static final Random _random = Random.secure();

  /// Returns a random, UUID-v4-shaped lowercase hex string
  /// (`xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`).
  ///
  /// Used for both the client idempotency key and the local draft id. It is not
  /// a cryptographic guarantee of uniqueness, but the 122 bits of entropy make a
  /// collision on one device negligible.
  static String v4() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    // Set the version (4) and variant (10xx) bits, per the v4 layout.
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes
        .map((b) => b.toRadixString(16).padLeft(2, '0'))
        .join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
        '${hex.substring(12, 16)}-${hex.substring(16, 20)}-'
        '${hex.substring(20)}';
  }
}
