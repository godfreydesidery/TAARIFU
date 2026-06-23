/// Unit tests for the **durable** disk-backed outbox ([FileOutboxStore]): the
/// queue must survive a cold start (a fresh instance over the same file
/// re-hydrates), preserve FIFO order, keep the **stable idempotency key** across
/// status transitions, and degrade safely on a corrupt file.
///
/// These pin the offline-submit durability invariant (PRD §15, §17, UC-D03): a
/// report drafted on a dead network is still queued — with the same key, so a
/// replay is de-duped — after the app process is killed and relaunched.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/storage/file_outbox_store.dart';
import 'package:taarifu_citizen/core/storage/outbox_entry.dart';

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
  group('FileOutboxStore (durable)', () {
    late Directory tempDir;
    late File file;

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp('outbox_test_');
      file = File('${tempDir.path}/outbox.json');
    });

    tearDown(() async {
      if (await tempDir.exists()) {
        await tempDir.delete(recursive: true);
      }
    });

    test('absent file → empty queue (cold start with no prior drafts)',
        () async {
      final store = FileOutboxStore(file: file);
      expect(await store.all(), isEmpty);
      expect(await store.pending(), isEmpty);
    });

    test('enqueue persists to disk and keeps FIFO order', () async {
      final store = FileOutboxStore(file: file);
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b'));
      final all = await store.all();
      expect(all.map((e) => e.localId), ['a', 'b']);
      // The file actually exists on disk (durable, not just in memory).
      expect(await file.exists(), isTrue);
    });

    test(
      'survives a COLD START: a fresh instance over the same file re-hydrates '
      'the queue with the stable idempotency key (no duplicate on replay)',
      () async {
        // First "process": draft two reports offline.
        final store1 = FileOutboxStore(file: file);
        await store1.enqueue(_entry('a'));
        await store1.enqueue(_entry('b', status: OutboxStatus.failed));

        // Second "process": the app was killed and relaunched — a brand-new
        // store instance reads the same file from disk.
        final store2 = FileOutboxStore(file: file);
        final all = await store2.all();
        expect(all.map((e) => e.localId), ['a', 'b']);
        // The idempotency keys are intact across the cold start → a replay is
        // de-duped by the backend, never a duplicate ticket (PRD §17).
        expect(all.map((e) => e.key), ['idem-a', 'idem-b']);
        expect(all[1].status, OutboxStatus.failed);
        expect(all.first.payload['title'], 'Maji yamekatika');
      },
    );

    test('pending() includes PENDING and FAILED, excludes SENDING', () async {
      final store = FileOutboxStore(file: file);
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b', status: OutboxStatus.failed));
      await store.enqueue(_entry('c', status: OutboxStatus.sending));
      final pending = await store.pending();
      expect(pending.map((e) => e.localId).toSet(), {'a', 'b'});
    });

    test('update replaces by localId and the change is persisted', () async {
      final store = FileOutboxStore(file: file);
      await store.enqueue(_entry('a'));
      final updated = (await store.all()).first.copyWith(
        status: OutboxStatus.failed,
        attempts: 2,
        lastError: 'timeout',
      );
      await store.update(updated);

      // Re-read from disk via a fresh instance: the update is durable and the
      // idempotency key is unchanged.
      final reread = (await FileOutboxStore(file: file).all()).first;
      expect(reread.status, OutboxStatus.failed);
      expect(reread.attempts, 2);
      expect(reread.key, 'idem-a');
    });

    test('remove deletes only the matching entry, durably', () async {
      final store = FileOutboxStore(file: file);
      await store.enqueue(_entry('a'));
      await store.enqueue(_entry('b'));
      await store.remove('a');
      expect((await store.all()).map((e) => e.localId), ['b']);
      // Durable: a fresh instance also sees only 'b'.
      expect(
        (await FileOutboxStore(file: file).all()).map((e) => e.localId),
        ['b'],
      );
    });

    test('a corrupt queue file degrades to empty rather than crashing',
        () async {
      await file.writeAsString('}{ not json at all');
      final store = FileOutboxStore(file: file);
      expect(await store.all(), isEmpty);
      // And the store is still usable: a new enqueue overwrites the bad file.
      await store.enqueue(_entry('a'));
      expect((await store.all()).map((e) => e.localId), ['a']);
    });

    test('concurrent enqueues are serialised — no draft is lost', () async {
      final store = FileOutboxStore(file: file);
      // Fire many enqueues without awaiting between them; the internal lock must
      // serialise the read-modify-write so none clobber each other.
      await Future.wait([
        for (var i = 0; i < 20; i++) store.enqueue(_entry('e$i')),
      ]);
      final all = await store.all();
      expect(all, hasLength(20));
      expect(all.map((e) => e.localId).toSet(), {
        for (var i = 0; i < 20; i++) 'e$i',
      });
    });

    test('persisted file is valid JSON of OutboxEntry.toJson shape', () async {
      final store = FileOutboxStore(file: file);
      await store.enqueue(_entry('a'));
      final decoded = jsonDecode(await file.readAsString());
      expect(decoded, isA<List<dynamic>>());
      final first = (decoded as List).first as Map<String, dynamic>;
      expect(first['localId'], 'a');
      expect(first['key'], 'idem-a');
      expect(first['kind'], OutboxEntry.kindReport);
    });
  });
}
