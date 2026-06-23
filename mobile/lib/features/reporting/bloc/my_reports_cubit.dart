/// Cubit for the "My reports" screen: the caller's filed reports plus any
/// offline drafts still queued for sync.
library;

import 'package:flutter_bloc/flutter_bloc.dart';

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

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  MyReportsState copyWith({
    MyReportsStatus? status,
    List<Report>? reports,
    List<OutboxEntry>? drafts,
    bool? syncing,
    Object? error,
  }) => MyReportsState(
    status: status ?? this.status,
    reports: reports ?? this.reports,
    drafts: drafts ?? this.drafts,
    syncing: syncing ?? this.syncing,
    error: error,
  );
}

/// Loads filed reports + queued drafts and drives the manual sync.
class MyReportsCubit extends Cubit<MyReportsState> {
  /// Creates the cubit over a [ReportRepository].
  MyReportsCubit({required ReportRepository repository})
    : _repository = repository,
      super(const MyReportsState());

  final ReportRepository _repository;

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

  /// Flushes queued drafts, then reloads so synced ones appear in the server list.
  Future<void> syncNow() async {
    emit(state.copyWith(syncing: true));
    try {
      await _repository.syncPending();
    } finally {
      final drafts = await _repository.pendingDrafts();
      emit(state.copyWith(syncing: false, drafts: drafts));
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
