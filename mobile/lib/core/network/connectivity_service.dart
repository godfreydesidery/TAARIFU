/// A thin connectivity probe used as a cheap pre-flight hint before a request.
///
/// WHY not trust `connectivity_plus` alone: a "connected" radio does not mean the
/// backend is reachable (captive portals, no data balance, dead 2G). This service
/// uses the radio state only as a *fast negative* — if the OS reports no network
/// at all, we fail fast with [OfflineException] and skip a doomed request (saving
/// the user's data and battery). A *positive* radio state is NOT trusted on its
/// own; the actual request is the real reachability test, and its timeout/HTTP
/// result is authoritative (PRD §15 — never trust `onConnectivityChanged` alone).
library;

import 'package:connectivity_plus/connectivity_plus.dart';

/// Reports whether the device currently has any network interface up.
class ConnectivityService {
  /// Creates the service over a [Connectivity] instance (injectable for tests).
  ConnectivityService({Connectivity? connectivity})
    : _connectivity = connectivity ?? Connectivity();

  final Connectivity _connectivity;

  /// Returns `true` if at least one non-`none` interface is reported.
  ///
  /// A `true` result is a *hint* only (the radio may be up but the link dead);
  /// a `false` result is reliable enough to short-circuit a request as offline.
  Future<bool> get isProbablyOnline async {
    final results = await _connectivity.checkConnectivity();
    return results.any((r) => r != ConnectivityResult.none);
  }

  /// A stream of connectivity changes, surfaced as a simple online/offline hint.
  ///
  /// Used to trigger a sync attempt when the radio comes back (the outbox flush
  /// still re-checks real reachability via the request itself).
  Stream<bool> get onlineChanges => _connectivity.onConnectivityChanged.map(
    (results) => results.any((r) => r != ConnectivityResult.none),
  );
}
