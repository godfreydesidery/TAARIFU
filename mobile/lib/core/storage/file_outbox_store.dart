/// A **disk-backed** [OutboxStore] that survives a cold start — the production
/// swap the in-memory store's library doc (and CENTRAL INTEGRATION NEEDS) flagged.
///
/// WHY a single JSON file (not Drift/Isar): the outbox is a small append/replace/
/// remove queue keyed by [OutboxEntry.localId], and an entry already serialises to
/// JSON ([OutboxEntry.toJson]). A flat JSON document plus an in-memory mirror is
/// the simplest thing that satisfies the requirement (KISS, CLAUDE.md §3) and is
/// the most **data/APK-frugal** choice — it adds only `path_provider` (a
/// first-party plugin, no codegen, no native SQLite) rather than a whole embedded
/// database a citizen on a tiny bundle must download (PRD §15 data-budget NFR). The
/// abstraction is unchanged, so this is a true drop-in: repositories and BLoCs that
/// depend on [OutboxStore] are untouched.
///
/// WHY it preserves the offline-submit invariants: durability is the whole point of
/// an offline draft — a report filed on a dead network must still be there after the
/// app is killed (UC-D03). Each persisted [OutboxEntry] keeps its **stable v4
/// idempotency key** across reloads and status transitions, so a draft replayed on
/// reconnect is de-duplicated by the backend and **never creates a duplicate
/// ticket** (PRD §17, §15). FIFO order is preserved by writing the list in order.
///
/// Durability detail: writes go to a temp sibling file that is then atomically
/// renamed over the target, so a crash mid-write can never leave a half-written,
/// unparseable queue (it leaves either the old or the new complete file). A
/// corrupt/absent file degrades to an empty queue rather than crashing the app —
/// losing at most the un-synced drafts, never the running app (fail-safe, §3).
///
/// It is NOT a secure store — a draft holds free text the citizen typed but never
/// PII like tokens (those live in [TokenStore]); attachments are referenced by
/// object-store key, not embedded (mirrors the in-memory store's contract).
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'outbox_entry.dart';
import 'outbox_store.dart';

/// A durable, file-backed [OutboxStore].
///
/// Holds the queue both on disk (a JSON array of [OutboxEntry.toJson] objects) and
/// in an in-memory mirror loaded lazily on first access, so reads are cheap and a
/// cold start re-hydrates the queue from disk.
class FileOutboxStore implements OutboxStore {
  /// Creates a store backed by [file].
  ///
  /// Production callers use [FileOutboxStore.inDirectory] to resolve the app
  /// documents directory via `path_provider`; tests inject a file in a temp
  /// directory so no platform channel is needed.
  FileOutboxStore({required File file}) : _file = file;

  /// The default queue file name under the chosen directory.
  static const String defaultFileName = 'outbox.json';

  /// Builds a store whose file is [defaultFileName] inside [directory].
  factory FileOutboxStore.inDirectory(Directory directory) =>
      FileOutboxStore(file: File('${directory.path}/$defaultFileName'));

  final File _file;

  /// The in-memory mirror; `null` until the first lazy load from disk.
  List<OutboxEntry>? _cache;

  /// Serialises all disk access so concurrent enqueue/flush calls never interleave
  /// a read-modify-write (which could drop or duplicate an entry). Each public
  /// method runs its body inside this single-flight chain.
  Future<void> _lock = Future<void>.value();

  /// Runs [action] after any in-flight operation completes, returning its result.
  ///
  /// WHY: enqueue/update/remove are read-modify-write on the same file; without
  /// serialisation a sync flush racing a new enqueue could lose a draft. A simple
  /// promise chain gives mutual exclusion without a third-party lock package.
  Future<T> _synchronized<T>(Future<T> Function() action) {
    final completer = Completer<T>();
    _lock = _lock.then((_) async {
      try {
        completer.complete(await action());
      } catch (e, st) {
        completer.completeError(e, st);
      }
    });
    return completer.future;
  }

  /// Loads (once) the persisted queue into [_cache], tolerating an absent or
  /// corrupt file by starting empty (fail-safe — never crash on a bad cache).
  Future<List<OutboxEntry>> _load() async {
    final cached = _cache;
    if (cached != null) {
      return cached;
    }
    final entries = <OutboxEntry>[];
    try {
      if (await _file.exists()) {
        final raw = await _file.readAsString();
        if (raw.trim().isNotEmpty) {
          final decoded = jsonDecode(raw);
          if (decoded is List) {
            for (final node in decoded) {
              if (node is Map<String, dynamic>) {
                entries.add(OutboxEntry.fromJson(node));
              }
            }
          }
        }
      }
    } on Object {
      // A corrupt/unreadable queue file must not brick the app: degrade to empty.
      entries.clear();
    }
    _cache = entries;
    return entries;
  }

  /// Atomically persists [_cache] to disk (temp file → rename), so a crash mid-write
  /// can never leave a half-written, unparseable queue.
  Future<void> _persist(List<OutboxEntry> entries) async {
    final dir = _file.parent;
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }
    final json = jsonEncode(entries.map((e) => e.toJson()).toList());
    final tmp = File('${_file.path}.tmp');
    await tmp.writeAsString(json, flush: true);
    await tmp.rename(_file.path);
  }

  @override
  Future<void> enqueue(OutboxEntry entry) => _synchronized(() async {
    final entries = await _load();
    entries.add(entry);
    await _persist(entries);
  });

  @override
  Future<List<OutboxEntry>> all() => _synchronized(() async {
    final entries = await _load();
    return List<OutboxEntry>.unmodifiable(entries);
  });

  @override
  Future<List<OutboxEntry>> pending() => _synchronized(() async {
    final entries = await _load();
    return entries
        .where(
          (e) =>
              e.status == OutboxStatus.pending ||
              e.status == OutboxStatus.failed,
        )
        .toList(growable: false);
  });

  @override
  Future<void> update(OutboxEntry entry) => _synchronized(() async {
    final entries = await _load();
    final i = entries.indexWhere((e) => e.localId == entry.localId);
    if (i >= 0) {
      entries[i] = entry;
      await _persist(entries);
    }
  });

  @override
  Future<void> remove(String localId) => _synchronized(() async {
    final entries = await _load();
    final before = entries.length;
    entries.removeWhere((e) => e.localId == localId);
    if (entries.length != before) {
      await _persist(entries);
    }
  });
}
