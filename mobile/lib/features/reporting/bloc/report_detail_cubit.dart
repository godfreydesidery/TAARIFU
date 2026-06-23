/// Cubit for the report tracking/detail screen: the report, its timeline, the
/// add-comment action, and the confirm/dispute-resolution action (US-3.2, 3.5).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/report_repository.dart';
import '../data/reporting_models.dart';

/// Load status for the detail screen.
enum ReportDetailStatus {
  /// Initial — not yet loaded.
  initial,

  /// A load is in flight.
  loading,

  /// Report + timeline loaded.
  loaded,

  /// The load failed — show retry.
  failure,
}

/// Immutable state for [ReportDetailCubit].
class ReportDetailState {
  /// Creates a state.
  const ReportDetailState({
    this.status = ReportDetailStatus.initial,
    this.report,
    this.timeline = const [],
    this.actionInFlight = false,
    this.error,
  });

  /// The current status.
  final ReportDetailStatus status;

  /// The loaded report, or `null`.
  final Report? report;

  /// The case timeline, oldest first.
  final List<CaseEvent> timeline;

  /// Whether a comment/confirm action is in flight (drives button disabling).
  final bool actionInFlight;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  ReportDetailState copyWith({
    ReportDetailStatus? status,
    Report? report,
    List<CaseEvent>? timeline,
    bool? actionInFlight,
    Object? error,
  }) => ReportDetailState(
    status: status ?? this.status,
    report: report ?? this.report,
    timeline: timeline ?? this.timeline,
    actionInFlight: actionInFlight ?? this.actionInFlight,
    error: error,
  );
}

/// Loads a report + timeline and performs the comment/confirm/dispute actions.
class ReportDetailCubit extends Cubit<ReportDetailState> {
  /// Creates the cubit over a [ReportRepository] for the report with [reportId].
  ReportDetailCubit({
    required ReportRepository repository,
    required String reportId,
  }) : _repository = repository,
       _reportId = reportId,
       super(const ReportDetailState());

  final ReportRepository _repository;
  final String _reportId;

  /// Loads the report and its timeline together.
  Future<void> load() async {
    emit(state.copyWith(status: ReportDetailStatus.loading));
    try {
      final report = await _repository.getMine(_reportId);
      final timeline = await _repository.timeline(_reportId);
      emit(
        ReportDetailState(
          status: ReportDetailStatus.loaded,
          report: report,
          timeline: timeline,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(status: ReportDetailStatus.failure, error: e));
    }
  }

  /// Adds info/a comment, then refreshes the timeline.
  Future<void> addComment(String message) async {
    emit(state.copyWith(actionInFlight: true));
    try {
      await _repository.addComment(_reportId, message);
      final timeline = await _repository.timeline(_reportId);
      emit(state.copyWith(actionInFlight: false, timeline: timeline));
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Confirms (close) or disputes (reopen) the resolution, then reloads.
  Future<void> confirmResolution({
    required bool confirmed,
    String? reason,
  }) async {
    emit(state.copyWith(actionInFlight: true));
    try {
      final updated = await _repository.confirmResolution(
        _reportId,
        confirmed: confirmed,
        reason: reason,
      );
      final timeline = await _repository.timeline(_reportId);
      emit(
        ReportDetailState(
          status: ReportDetailStatus.loaded,
          report: updated,
          timeline: timeline,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }
}
