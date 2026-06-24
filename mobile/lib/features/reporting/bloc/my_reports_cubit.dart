/// Cubit for the "My reports" screen: the caller's filed reports plus any
/// offline drafts still queued for sync. Auto-flushes the outbox when
/// connectivity returns (retry-on-reconnect), de-duplicated by the idempotency
/// key so a replay never creates a second ticket (PRD §15, §17).
library;

import 'dart:async';

import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/connectivity_service.dart';
import '../../../core/storage/outbox_entry.dart';
import '../data/report_repository.dart';
import '../data/reporting_models.dart';

/// Load status for the my-reports screen.
enum MyReportsStatus {
  /// Initial — not yet loaded.
  initial,

  /// A request is in flight.
  loading,

  /// Loaded (the server list may be empty; drafts may still be present).
  loaded,

  /// The server list failed to load — show retry (drafts still shown if any).
  failure,
}

/// Immutable state for [MyReportsCubit].
class MyReportsState {
  /// Creates a state.
  const MyReportsState({
    this.status = MyReportsStatus.initial,
    this.reports = const [],
    this.drafts = const [],
    this.syncing = false,
    this.justSyncedCount = 0,
    this.error,
  });

  /// The current status.
  final MyReportsStatus status;

  /// The caller's filed reports (server), newest first.
  final List<Report> reports;

  /// Offline drafts still queued for sync (oldest first).
  final List<OutboxEntry> drafts;

  /// Whether a sync flush is currently running.
  final bool syncing;

  /// How many drafts the *last* flush sent — drives a "synced N" snackbar so the
  /// citizen sees the reconnect retry worked. Zero when there is nothing to show.
  final int justSyncedCount;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  MyReportsState copyWith({
    MyReportsStatus? status,
    List<Report>? reports,
    List<OutboxEntry>? drafts,
    bool? syncing,
    int? justSyncedCount,
    Object? error,
  }) => MyReportsState(
    status: status ?? this.status,
    reports: reports ?? this.reports,
    drafts: drafts ?? this.drafts,
    syncing: syncing ?? this.syncing,
    justSyncedCount: justSyncedCount ?? this.justSyncedCount,
    error: error,
  );
}

/// Loads filed reports + queued drafts and drives the manual + auto sync.
class MyReportsCubit extends Cubit<MyReportsState> {
  /// Creates the cubit over a [ReportRepository] and, optionally, a
  /// [ConnectivityService] whose reconnect events trigger an automatic flush.
  ///
  /// The connectivity hint is only a *trigger*; the flush itself re-checks real
  /// reachability via the request (a "connected" radio may still be a dead link),
  /// and every replay reuses the draft's idempotency key, so a reconnect-driven
  /// retry never duplicates a ticket (PRD §15, §17).
  MyReportsCubit({
    required ReportRepository repository,
    ConnectivityService? connectivity,
  }) : _repository = repository,
       super(const MyReportsState()) {
    final stream = connectivity?.onlineChanges;
    if (stream != null) {
      _connSub = stream.listen((online) {
        if (online && !state.syncing && state.drafts.isNotEmpty) {
          syncNow();
        }
      });
    }
  }

  final ReportRepository _repository;
  StreamSubscription<bool>? _connSub;

  @override
  Future<void> close() async {
    await _connSub?.cancel();
    return super.close();
  }

  /// Loads drafts (always available, even offline) then the server list.
  ///
  /// Drafts are read first and unconditionally so the screen still shows the
  /// citizen's queued reports when the network read fails (offline-first).
  Future<void> load() async {
    emit(state.copyWith(status: MyReportsStatus.loading));
    final drafts = await _repository.pendingDrafts();
    try {
      final reports = await _repository.listMine();
      emit(
        MyReportsState(
          status: MyReportsStatus.loaded,
          reports: reports,
          drafts: drafts,
        ),
      );
    } on Object catch (e) {
      emit(
        MyReportsState(
          status: MyReportsStatus.failure,
          drafts: drafts,
          error: e,
        ),
      );
    }
  }

  /// Flushes queued drafts, then reloads so synced ones appear in the server
  /// list. Exposes how many were sent via [MyReportsState.justSyncedCount] so the
  /// UI can confirm the retry worked (reconnect feedback).
  Future<void> syncNow() async {
    emit(state.copyWith(syncing: true, justSyncedCount: 0));
    var synced = 0;
    try {
      synced = await _repository.syncPending();
    } finally {
      final drafts = await _repository.pendingDrafts();
      emit(
        state.copyWith(
          syncing: false,
          drafts: drafts,
          justSyncedCount: synced,
        ),
      );
      await load();
    }
  }

  /// Discards a queued draft and refreshes the drafts list.
  Future<void> discardDraft(String localId) async {
    await _repository.discardDraft(localId);
    final drafts = await _repository.pendingDrafts();
    emit(state.copyWith(drafts: drafts));
  }
}
