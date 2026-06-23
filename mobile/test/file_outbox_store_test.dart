/// Tests the **durable** offline outbox ([FileOutboxStore]): it must persist the
/// queue to disk and reconstruct it on a fresh instance, so a report drafted
/// offline survives a cold start (PRD §15 offline-first, UC-D03). The critical
/// invariant: the stable idempotency key is preserved across the relaunch, so a
/// replayed submit is de-duped by the backend (PRD §17) — never a duplicate
/// ticket.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/storage/file_outbox_store.dart';
import 'package:taarifu_citizen/core/storage/outbox_entry.dart';

OutboxEntry _entry(String id, {OutboxStatus status = OutboxStatus.pending}) =>
    OutboxEntry(
      localId: id,
      key: 'idem-$id',
      kind: OutboxEntry.kindReport,
      payload: const {'title': 'Maji yamekatika', 'wardId': 'w-1'},
      createdAt: DateTime.utc(2026, 6, 23),
      status: status,
    );

void main() {
  group('FileOutboxStore (durable, cold-start safe)', () {
    late Directory tempDir;
    late File backingFile;

    // A fresh store over the SAME file models the same app across relaunches.
    FileOutboxStore newStore() =>
        FileOutboxStore(fileResolver: () async => backingFile);

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp('taarifu_outbox_test');
      backingFile = File('${tempDir.path}/outbox.json');
    });

    tearDown(() async {
      if (await tempDir.exists()) {
        await tempDir.delete(recursive: true);
      }
    });

    test('enqueue then all() returns entries in FIFO order', () async {
      final store = newStore();
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b'));
      final all = await store.all();
      expect(all.map((e) => e.localId), ['a', 'b']);
    });

    test('pending() includes PENDING and FAILED, excludes SENDING', () async {
      final store = newStore();
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b', status: OutboxStatus.failed));
      await store.enqueue(_entry('c', status: OutboxStatus.sending));
      final pending = await store.pending();
      expect(pending.map((e) => e.localId).toSet(), {'a', 'b'});
    });

    test('update replaces the entry with the same localId, keeping the key',
        () async {
      final store = newStore();
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
      expect(after.key, 'idem-a');
    });

    test('remove deletes only the matching entry', () async {
      final store = newStore();
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b'));
      await store.remove('a');
      final all = await store.all();
      expect(all.map((e) => e.localId), ['b']);
    });

    test(
      'a queued draft SURVIVES a cold start: a brand-new store over the same '
      'file rebuilds the queue WITH its idempotency key intact (de-dupe holds)',
      () async {
        // App run #1: draft two reports offline, then the app is killed.
        final first = newStore();
        await first.enqueue(_entry('a'));
        await first.enqueue(_entry('b', status: OutboxStatus.failed));

        // App run #2: a fresh process, fresh store, same backing file.
        final second = newStore();
        final pending = await second.pending();
        expect(pending.map((e) => e.localId), ['a', 'b']);
        // The idempotency keys minted at draft time persisted verbatim — this is
        // what keeps a post-relaunch replay from creating a duplicate ticket.
        expect(pending.map((e) => e.key).toList(), ['idem-a', 'idem-b']);
        expect(pending.first.payload['wardId'], 'w-1');
        expect(pending.first.createdAt, DateTime.utc(2026, 6, 23));
      },
    );

    test('a removal is persisted: after relaunch the drained entry is gone',
        () async {
      final first = newStore();
      await first.enqueue(_entry('a'));
      await first.enqueue(_entry('b'));
      await first.remove('a'); // synced & drained

      final second = newStore();
      final all = await second.all();
      expect(all.map((e) => e.localId), ['b']);
    });

    test('a corrupt/garbage file degrades to an empty queue, never crashes',
        () async {
      await backingFile.writeAsString('{ this is not valid json [');
      final store = newStore();
      expect(await store.all(), isEmpty);
      // …and the store is still usable after recovering from corruption.
      await store.enqueue(_entry('a'));
      expect((await store.all()).map((e) => e.localId), ['a']);
    });

    test('a missing file is treated as an empty queue', () async {
      final store = newStore();
      expect(await store.all(), isEmpty);
      expect(await store.pending(), isEmpty);
    });
  });
}
