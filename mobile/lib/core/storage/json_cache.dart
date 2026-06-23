/// A minimal offline-first read cache: stores the last successful JSON payload
/// for a key so a screen can render instantly (and survive a dead network) while
/// a fresh fetch is in flight.
///
/// WHY this seam now (even before a real DB): the foundation must *prove the
/// offline-first pattern* — read-through cache for reads, and a later
/// outbox/pending-mutations queue for writes (PRD §15 offline-first). This is a
/// deliberately tiny in-memory implementation behind an interface; the
/// production swap to Drift/Isar (and an idempotency-keyed outbox) is a
/// drop-in replacement that does not touch the BLoCs that depend on the
/// interface. See CENTRAL INTEGRATION NEEDS in README.
///
/// It is NOT a secure store — never cache PII or tokens here (PRD §18). Tokens
/// live in [TokenStore]; this is for public, non-sensitive read payloads (feed,
/// representatives, geography).
library;

/// Read-cache contract: get/put the last-known JSON for a string key.
abstract interface class JsonCache {
  /// Returns the cached decoded JSON for [key], or `null` on a miss.
  Future<Object?> read(String key);

  /// Stores [value] (a JSON-encodable object) under [key].
  Future<void> write(String key, Object? value);

  /// Removes the entry for [key].
  Future<void> remove(String key);
}

/// In-memory [JsonCache] used by the foundation slice and tests.
///
/// Lives only for the app session. The production implementation will persist
/// to disk (Drift/Isar) so the cache survives a cold start, with the same API.
class InMemoryJsonCache implements JsonCache {
  final Map<String, Object?> _store = <String, Object?>{};

  @override
  Future<Object?> read(String key) async => _store[key];

  @override
  Future<void> write(String key, Object? value) async {
    _store[key] = value;
  }

  @override
  Future<void> remove(String key) async {
    _store.remove(key);
  }
}
