/// Unit tests for the offline outbox: enqueue → pending → update → remove, and
/// the JSON round-trip an entry must survive for the future disk-backed store.
///
/// These pin the core offline-submit invariant (PRD §15, §17): a queued draft
/// keeps its idempotency key across status transitions so a replay is de-duped.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/storage/outbox_entry.dart';
import 'package:taarifu_citizen/core/storage/outbox_store.dart';

OutboxEntry _entry(String id, {OutboxStatus status = OutboxStatus.pending}) =>
    OutboxEntry(
      localId: id,
      key: 'idem-$id',
      kind: OutboxEntry.kindReport,
      payload: const {'title': 'Maji yamekatika'},
      createdAt: DateTime.utc(2026, 6, 23),
      status: status,
    );

void main() {
  group('InMemoryOutboxStore', () {
    late InMemoryOutboxStore store;

    setUp(() => store = InMemoryOutboxStore());

    test('enqueue then all() returns entries in FIFO order', () async {
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b'));
      final all = await store.all();
      expect(all.map((e) => e.localId), ['a', 'b']);
    });

    test('pending() includes PENDING and FAILED, excludes SENDING', () async {
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b', status: OutboxStatus.failed));
      await store.enqueue(_entry('c', status: OutboxStatus.sending));
      final pending = await store.pending();
      expect(pending.map((e) => e.localId).toSet(), {'a', 'b'});
    });

    test('update replaces the entry with the same localId, keeping the key',
        () async {
      await store.enqueue(_entry('a'));
      final updated = (await store.all()).first.copyWith(
        status: OutboxStatus.failed,
        attempts: 2,
        lastError: 'timeout',
      );
      await store.update(updated);
      final after = (await store.all()).first;
      expect(after.status, OutboxStatus.failed);
      expect(after.attempts, 2);
      // The idempotency key MUST be stable across a retry update (de-dupe).
      expect(after.key, 'idem-a');
    });

    test('remove deletes only the matching entry', () async {
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b'));
      await store.remove('a');
      final all = await store.all();
      expect(all.map((e) => e.localId), ['b']);
    });

    test('OutboxEntry survives a JSON round-trip', () {
      final e = _entry('a', status: OutboxStatus.failed).copyWith(attempts: 1);
      final back = OutboxEntry.fromJson(e.toJson());
      expect(back.localId, e.localId);
      expect(back.key, e.key);
      expect(back.kind, e.kind);
      expect(back.status, OutboxStatus.failed);
      expect(back.attempts, 1);
      expect(back.payload['title'], 'Maji yamekatika');
    });
  });
}
