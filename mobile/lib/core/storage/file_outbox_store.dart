/// The **durable** offline outbox: a disk-backed [OutboxStore] that survives a
/// cold start, so a report drafted offline is not lost if the app is killed
/// before it could sync (PRD §15 offline-first, UC-D03).
///
/// WHY a single JSON file (not Drift/Isar/Hive): the dependency budget is tight —
/// every package grows the APK a citizen on a small data bundle must download
/// (PRD §15, pubspec rationale). The outbox is a small FIFO queue of report
/// drafts, never a queryable dataset, so a whole-file read/rewrite on each
/// mutation is correct, simple (KISS, CLAUDE.md §3), and avoids a code-gen DB
/// for a handful of rows. [OutboxEntry] already serialises to/from JSON for
/// exactly this. If the queue ever grows large or needs indexed queries, this is
/// a drop-in swap to Drift behind the same [OutboxStore] interface — no
/// repository or BLoC changes (clean boundaries).
///
/// Durability & safety properties:
///   * **Survives cold start** — every mutation is flushed to disk immediately,
///     so the queue is reconstructed on next launch from the file.
///   * **Atomic writes** — we write to a temp file then [File.rename] over the
///     target, so a crash mid-write never corrupts the queue (rename is atomic
///     on the same volume); a half-written temp is simply ignored next launch.
///   * **In-memory mirror** — the parsed list is cached after load so reads are
///     synchronous-fast; the file is the source of truth across launches.
///   * **Stable idempotency key** — entries are persisted verbatim, so a draft
///     keeps the SAME key across a kill/relaunch/retry and the backend de-dupes
///     a replayed submit (PRD §17) — the whole point of cold-start durability.
///
/// It is NOT a secure store — a draft holds free text the citizen typed but
/// never tokens/PII (those live in [TokenStore]); attachments are referenced by
/// object-store key, not embedded (PRD §18).
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'outbox_entry.dart';
import 'outbox_store.dart';

/// Abstraction over "where the queue file lives", so tests can point it at a
/// temp dir and production at the app-support directory (via `path_provider`),
/// without this class importing Flutter plugins (keeps it unit-testable).
typedef OutboxFileResolver = Future<File> Function();

/// A disk-backed [OutboxStore] persisting the queue to a single JSON file.
class FileOutboxStore implements OutboxStore {
  /// Creates the store over a [fileResolver] that yields the backing file.
  ///
  /// The file need not exist yet; it is created lazily on the first write. The
  /// resolver is async so production can await `path_provider`'s directory.
  FileOutboxStore({required OutboxFileResolver fileResolver})
    : _resolveFile = fileResolver;

  final OutboxFileResolver _resolveFile;

  /// In-memory mirror of the persisted queue (FIFO). `null` until first loaded.
  List<OutboxEntry>? _cache;

  /// Serialises load/mutation so concurrent flush + enqueue can't interleave a
  /// read-modify-write and lose an entry (offline submit can race with sync).
  Future<void> _lock = Future<void>.value();

  /// Runs [action] under the single-writer lock, returning its result.
  Future<T> _synchronized<T>(Future<T> Function() action) {
    final completer = Completer<T>();
    _lock = _lock.then((_) async {
      try {
        completer.complete(await action());
      } catch (e, s) {
        completer.completeError(e, s);
      }
    });
    return completer.future;
  }

  /// Loads (once) the queue from disk into [_cache]; tolerates a missing or
  /// corrupt file by starting from an empty queue (a draft we can't parse is a
  /// loss already; we must not brick the outbox over it).
  Future<List<OutboxEntry>> _load() async {
    final cached = _cache;
    if (cached != null) {
      return cached;
    }
    final entries = <OutboxEntry>[];
    try {
      final file = await _resolveFile();
      if (await file.exists()) {
        final raw = await file.readAsString();
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
      // Unreadable/corrupt file → start clean rather than crash the app.
      entries.clear();
    }
    _cache = entries;
    return entries;
  }

  /// Atomically persists the current [_cache] to disk (temp file + rename).
  Future<void> _flush() async {
    final entries = _cache ?? const <OutboxEntry>[];
    final file = await _resolveFile();
    await file.parent.create(recursive: true);
    final json = jsonEncode(entries.map((e) => e.toJson()).toList());
    final tmp = File('${file.path}.tmp');
    await tmp.writeAsString(json, flush: true);
    // Atomic swap: a crash before this leaves the old (valid) file untouched;
    // after it, the new file is complete. No window of a half-written queue.
    await tmp.rename(file.path);
  }

  @override
  Future<void> enqueue(OutboxEntry entry) => _synchronized(() async {
    final entries = await _load();
    entries.add(entry);
    await _flush();
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
      await _flush();
    }
  });

  @override
  Future<void> remove(String localId) => _synchronized(() async {
    final entries = await _load();
    final before = entries.length;
    entries.removeWhere((e) => e.localId == localId);
    if (entries.length != before) {
      await _flush();
    }
  });
}
