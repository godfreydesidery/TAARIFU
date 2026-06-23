/// Cubit driving the "Report an issue" form: loads the category picker and
/// files the report (online → ticket; offline → queued draft).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/category_repository.dart';
import '../data/report_repository.dart';
import '../data/reporting_models.dart';

/// Phases of the report-form flow.
enum ReportFormStatus {
  /// Loading the category list.
  loadingCategories,

  /// Categories ready — the form is editable.
  ready,

  /// The category list failed to load — show retry (the citizen can't pick yet).
  categoriesFailed,

  /// A submit is in flight.
  submitting,

  /// Filed on the server — show the ticket code.
  filed,

  /// Saved to the offline outbox — show the "will send when online" copy.
  queued,

  /// The submit was rejected by the server (a real domain error to show).
  submitFailed,
}

/// Immutable state for [ReportFormCubit].
class ReportFormState {
  /// Creates a state.
  const ReportFormState({
    this.status = ReportFormStatus.loadingCategories,
    this.categories = const [],
    this.filed,
    this.error,
  });

  /// The current phase.
  final ReportFormStatus status;

  /// The loaded category options.
  final List<IssueCategory> categories;

  /// The filed report (when [ReportFormStatus.filed]), or `null`.
  final Report? filed;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  ReportFormState copyWith({
    ReportFormStatus? status,
    List<IssueCategory>? categories,
    Report? filed,
    Object? error,
  }) => ReportFormState(
    status: status ?? this.status,
    categories: categories ?? this.categories,
    filed: filed ?? this.filed,
    error: error,
  );
}

/// Loads categories and files (or queues) a report.
class ReportFormCubit extends Cubit<ReportFormState> {
  /// Creates the cubit over the category + report repositories.
  ReportFormCubit({
    required CategoryRepository categoryRepository,
    required ReportRepository reportRepository,
  }) : _categories = categoryRepository,
       _reports = reportRepository,
       super(const ReportFormState());

  final CategoryRepository _categories;
  final ReportRepository _reports;

  /// Loads the category picker. Tolerant of offline (served from cache).
  Future<void> loadCategories() async {
    emit(state.copyWith(status: ReportFormStatus.loadingCategories));
    try {
      final cats = await _categories.listActive();
      emit(
        ReportFormState(status: ReportFormStatus.ready, categories: cats),
      );
    } on Object catch (e) {
      emit(
        state.copyWith(status: ReportFormStatus.categoriesFailed, error: e),
      );
    }
  }

  /// Files [draft]; on offline/timeout it is queued and the UI says so.
  Future<void> submit(ReportDraft draft) async {
    emit(state.copyWith(status: ReportFormStatus.submitting));
    try {
      final outcome = await _reports.fileReport(draft);
      if (outcome.queued) {
        emit(state.copyWith(status: ReportFormStatus.queued));
      } else {
        emit(
          state.copyWith(
            status: ReportFormStatus.filed,
            filed: outcome.report,
          ),
        );
      }
    } on Object catch (e) {
      emit(state.copyWith(status: ReportFormStatus.submitFailed, error: e));
    }
  }
}
