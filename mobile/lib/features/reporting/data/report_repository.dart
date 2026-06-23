/// Repository for filing and tracking citizen reports (PRD §10 Epic M3,
/// UC-D01/D03/D05/D11-13).
///
/// Responsibility: turn the report use-cases into [ApiClient] calls against the
/// real `ReportController`, AND own the **offline-first submit** path: when the
/// device is offline (or the submit fails transiently) the draft is enqueued in
/// the [OutboxStore] with a client idempotency key and replayed later. Because
/// the key is stable across retries, a replayed submit is de-duplicated by the
/// backend — a report filed offline is never duplicated on sync (PRD §17, §15).
///
/// The BLoC depends on this repository, never on `ApiClient`/`Dio` directly
/// (clean layering, CLAUDE.md §3).
library;

import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/storage/outbox_entry.dart';
import '../../../core/storage/outbox_store.dart';
import '../../../core/util/id_generator.dart';
import 'reporting_models.dart';

/// The outcome of a file-report attempt: either filed on the server now, or
/// queued in the outbox for later sync.
class FileOutcome {
  /// Creates an outcome.
  const FileOutcome._({this.report, this.queued = false});

  /// A report that was successfully filed on the server now.
  const FileOutcome.filed(Report report) : this._(report: report);

  /// A draft that was queued offline (no server ticket yet).
  const FileOutcome.queued() : this._(queued: true);

  /// The filed report, or `null` when [queued].
  final Report? report;

  /// Whether the submit was queued (offline) rather than filed.
  final bool queued;
}

/// Files and tracks the caller's own reports, with an offline draft queue.
class ReportRepository {
  /// Creates the repository over an [ApiClient] and an [OutboxStore].
  ReportRepository({required ApiClient apiClient, required OutboxStore outbox})
    : _api = apiClient,
      _outbox = outbox;

  final ApiClient _api;
  final OutboxStore _outbox;

  /// Files a report (US-3.1, UC-D01), falling back to the offline outbox.
  ///
  /// Maps to `POST /reports` (`201`, T2). A fresh idempotency key is minted per
  /// draft. On [OfflineException] (and only that — a domain 4xx is a real
  /// rejection the citizen must see) the draft is enqueued and a queued outcome
  /// is returned so the UI can say "saved, will send when you're online".
  Future<FileOutcome> fileReport(ReportDraft draft) async {
    final key = IdGenerator.v4();
    final body = draft.toRequestBody();
    try {
      final result = await _api.post<Report>(
        '/reports',
        body: body,
        idempotencyKey: key,
        parser: (data) => Report.fromJson(data! as Map<String, dynamic>),
      );
      return FileOutcome.filed(result.data);
    } on OfflineException {
      await _enqueueDraft(key, body);
      return const FileOutcome.queued();
    } on TimeoutException {
      // A timeout on a write is ambiguous (the server may or may not have it).
      // The idempotency key makes a replay safe, so queue rather than risk loss.
      await _enqueueDraft(key, body);
      return const FileOutcome.queued();
    }
  }

  /// Lists drafts still waiting to sync (oldest first) — the Drafts view.
  Future<List<OutboxEntry>> pendingDrafts() => _outbox.pending();

  /// Attempts to flush all pending report drafts (called when connectivity
  /// returns or the citizen taps "sync now").
  ///
  /// Each entry is replayed with its ORIGINAL idempotency key, so a draft the
  /// server already accepted (but whose response we never saw) is de-duplicated
  /// rather than duplicated. Returns the number successfully synced.
  Future<int> syncPending() async {
    var synced = 0;
    for (final entry in await _outbox.pending()) {
      if (entry.kind != OutboxEntry.kindReport) {
        continue;
      }
      await _outbox.update(entry.copyWith(status: OutboxStatus.sending));
      try {
        await _api.post<Report>(
          '/reports',
          body: entry.payload,
          idempotencyKey: entry.key,
          parser: (data) => Report.fromJson(data! as Map<String, dynamic>),
        );
        await _outbox.remove(entry.localId);
        synced++;
      } on OfflineException {
        // Still offline — leave it pending, stop the flush (others will fail too).
        await _outbox.update(entry.copyWith(status: OutboxStatus.pending));
        break;
      } on ApiException catch (e) {
        // A transient/unknown failure: mark failed for retry; a domain error
        // (e.g. category removed) also lands here and is surfaced in the UI.
        await _outbox.update(
          entry.copyWith(
            status: OutboxStatus.failed,
            attempts: entry.attempts + 1,
            lastError: e.toString(),
          ),
        );
      }
    }
    return synced;
  }

  /// Discards a queued draft the citizen no longer wants (UC-D19 withdraw, local).
  Future<void> discardDraft(String localId) => _outbox.remove(localId);

  /// Lists the caller's own reports, newest first (US-3.2).
  ///
  /// Maps to `GET /reports?page=&size=&sort=`. Authenticated; returns only the
  /// caller's reports (the backend scopes by the bearer identity).
  Future<List<Report>> listMine({int page = 0, int size = 20}) async {
    final result = await _api.get<List<Map<String, dynamic>>>(
      '/reports',
      query: {'page': page, 'size': size, 'sort': 'createdAt,desc'},
      parser: _asMapList,
    );
    return result.data.map(Report.fromJson).toList(growable: false);
  }

  /// Fetches one of the caller's own reports (US-3.2).
  ///
  /// Maps to `GET /reports/{reportId}`.
  Future<Report> getMine(String reportId) async {
    final result = await _api.get<Report>(
      '/reports/$reportId',
      parser: (data) => Report.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Loads the report's timeline (US-3.2, UC-D05).
  ///
  /// Maps to `GET /reports/{reportId}/timeline`.
  Future<List<CaseEvent>> timeline(String reportId) async {
    final result = await _api.get<List<Map<String, dynamic>>>(
      '/reports/$reportId/timeline',
      query: {'page': 0, 'size': 50, 'sort': 'createdAt,asc'},
      parser: _asMapList,
    );
    return result.data.map(CaseEvent.fromJson).toList(growable: false);
  }

  /// Adds info/a comment to the caller's report (US-3.2).
  ///
  /// Maps to `POST /reports/{reportId}/comments`. Always a public timeline event;
  /// if the case was AWAITING_INFO this is the reply that resumes work.
  Future<CaseEvent> addComment(String reportId, String message) async {
    final result = await _api.post<CaseEvent>(
      '/reports/$reportId/comments',
      body: {'message': message},
      parser: (data) => CaseEvent.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Confirms or disputes a resolution (US-3.5, UC-D11/12/13).
  ///
  /// Maps to `POST /reports/{reportId}/confirmation`. `confirmed = true` closes
  /// the case; `false` disputes it (→ REOPENED), optionally with a [reason].
  Future<Report> confirmResolution(
    String reportId, {
    required bool confirmed,
    String? reason,
  }) async {
    final result = await _api.post<Report>(
      '/reports/$reportId/confirmation',
      body: {'confirmed': confirmed, 'reason': ?reason},
      parser: (data) => Report.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  Future<void> _enqueueDraft(String key, Map<String, dynamic> body) {
    return _outbox.enqueue(
      OutboxEntry(
        localId: IdGenerator.v4(),
        key: key,
        kind: OutboxEntry.kindReport,
        payload: body,
        createdAt: DateTime.now().toUtc(),
      ),
    );
  }

  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
