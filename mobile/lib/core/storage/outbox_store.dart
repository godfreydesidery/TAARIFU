/// The offline write seam: a pending-mutations **outbox** that mirrors the
/// backend's transactional-outbox thinking on the client.
///
/// WHY this exists (PRD §15 offline-first, UC-D03): a citizen drafts a report on
/// a bus with no signal. The submit cannot reach the server, so it is **enqueued
/// here** with a client-generated idempotency key and replayed when connectivity
/// returns. Because the key is stable across retries, the backend de-duplicates a
/// replayed submit — a report filed offline is **never** duplicated on sync
/// (ARCHITECTURE §5.4, PRD §17 idempotency).
///
/// This is the write-side analogue of [JsonCache]'s read-through cache. Like that
/// seam, the default implementation here is deliberately **in-memory** behind an
/// interface: it proves the queue/replay pattern end-to-end while the production
/// swap to a durable store (Drift/Isar, surviving cold start) is a drop-in that
/// does not touch the repositories or BLoCs depending on this interface. The need
/// for cold-start durability is flagged under CENTRAL INTEGRATION NEEDS.
///
/// It is NOT a secure store — a draft may contain free text the citizen typed but
/// never PII like tokens (those live in [TokenStore]); attachments are referenced
/// by object-store key, not embedded.
library;

import 'outbox_entry.dart';

/// Pending-mutations queue contract: enqueue, list, update status, and remove.
abstract interface class OutboxStore {
  /// Appends [entry] to the queue (e.g. a report draft awaiting sync).
  Future<void> enqueue(OutboxEntry entry);

  /// Returns all queued entries, oldest first (FIFO replay order).
  Future<List<OutboxEntry>> all();

  /// Returns only entries that still need sending (PENDING or FAILED).
  Future<List<OutboxEntry>> pending();

  /// Replaces the entry with the same [OutboxEntry.localId] (status updates).
  Future<void> update(OutboxEntry entry);

  /// Removes the entry with [localId] (after a confirmed successful sync).
  Future<void> remove(String localId);
}

/// In-memory [OutboxStore] used by the foundation slice and tests.
///
/// Lives only for the app session. The production implementation persists to disk
/// so a draft survives the app being killed before it could sync — the whole
/// point of an offline draft (see the library doc).
class InMemoryOutboxStore implements OutboxStore {
  final List<OutboxEntry> _entries = <OutboxEntry>[];

  @override
  Future<void> enqueue(OutboxEntry entry) async {
    _entries.add(entry);
  }

  @override
  Future<List<OutboxEntry>> all() async =>
      List<OutboxEntry>.unmodifiable(_entries);

  @override
  Future<List<OutboxEntry>> pending() async => _entries
      .where(
        (e) =>
            e.status == OutboxStatus.pending || e.status == OutboxStatus.failed,
      )
      .toList(growable: false);

  @override
  Future<void> update(OutboxEntry entry) async {
    final i = _entries.indexWhere((e) => e.localId == entry.localId);
    if (i >= 0) {
      _entries[i] = entry;
    }
  }

  @override
  Future<void> remove(String localId) async {
    _entries.removeWhere((e) => e.localId == localId);
  }
}
