/// Cubit driving the "Report an issue" form: loads the category picker, holds
/// captured attachments, and files the report (online → ticket; offline →
/// queued draft).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/attachment_models.dart';
import '../data/attachment_service.dart';
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
    this.attachments = const [],
    this.attachmentBusy = false,
    this.filed,
    this.error,
    this.attachmentError,
  });

  /// The current phase.
  final ReportFormStatus status;

  /// The loaded category options.
  final List<IssueCategory> categories;

  /// Captured attachments held for this draft (refs added at submit time).
  final List<PendingAttachment> attachments;

  /// Whether a capture is in flight (drives the add-photo spinner).
  final bool attachmentBusy;

  /// The filed report (when [ReportFormStatus.filed]), or `null`.
  final Report? filed;

  /// The caught submit/category failure to localise, or `null`.
  final Object? error;

  /// A transient attachment-capture failure to surface, or `null`.
  final Object? attachmentError;

  /// Returns a copy with overrides.
  ReportFormState copyWith({
    ReportFormStatus? status,
    List<IssueCategory>? categories,
    List<PendingAttachment>? attachments,
    bool? attachmentBusy,
    Report? filed,
    Object? error,
    Object? attachmentError,
  }) => ReportFormState(
    status: status ?? this.status,
    categories: categories ?? this.categories,
    attachments: attachments ?? this.attachments,
    attachmentBusy: attachmentBusy ?? this.attachmentBusy,
    filed: filed ?? this.filed,
    error: error,
    attachmentError: attachmentError,
  );
}

/// Loads categories, captures attachments, and files (or queues) a report.
class ReportFormCubit extends Cubit<ReportFormState> {
  /// Creates the cubit over the category + report repositories and the
  /// (injectable) attachment-capture seam.
  ReportFormCubit({
    required CategoryRepository categoryRepository,
    required ReportRepository reportRepository,
    AttachmentService? attachmentService,
  }) : _categories = categoryRepository,
       _reports = reportRepository,
       _attachments = attachmentService ?? const UnavailableAttachmentService(),
       super(const ReportFormState());

  final CategoryRepository _categories;
  final ReportRepository _reports;
  final AttachmentService _attachments;

  /// Whether real attachment capture is available (drives the UI affordance).
  bool get attachmentsAvailable => _attachments.isAvailable;

  /// Loads the category picker. Tolerant of offline (served from cache).
  Future<void> loadCategories() async {
    emit(state.copyWith(status: ReportFormStatus.loadingCategories));
    try {
      final cats = await _categories.listActive();
      emit(ReportFormState(status: ReportFormStatus.ready, categories: cats));
    } on Object catch (e) {
      emit(state.copyWith(status: ReportFormStatus.categoriesFailed, error: e));
    }
  }

  /// Captures a photo from [source] and appends it to the draft's attachments.
  ///
  /// A failed/cancelled capture surfaces [ReportFormState.attachmentError] for a
  /// localised, non-fatal message — it never blocks filing the report (which is
  /// the primary, attachment-optional path).
  Future<void> addAttachment(AttachmentSource source) async {
    emit(state.copyWith(attachmentBusy: true));
    try {
      final captured = await _attachments.capture(source);
      emit(
        state.copyWith(
          attachmentBusy: false,
          attachments: [...state.attachments, captured],
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(attachmentBusy: false, attachmentError: e));
    }
  }

  /// Removes a held attachment by its local path.
  void removeAttachment(String localPath) {
    emit(
      state.copyWith(
        attachments: state.attachments
            .where((a) => a.localPath != localPath)
            .toList(growable: false),
      ),
    );
  }

  /// Files [draft]; on offline/timeout it is queued and the UI says so.
  ///
  /// The held attachments' refs are attached here so a draft (online or queued)
  /// carries them; before the upload endpoint exists they are `local:` refs the
  /// repository resolves at sync time (see [PendingAttachment.ref]).
  Future<void> submit(ReportDraft draft) async {
    emit(state.copyWith(status: ReportFormStatus.submitting));
    final withRefs = draft.withAttachmentRefs(
      state.attachments.map((a) => a.ref).toList(growable: false),
    );
    try {
      final outcome = await _reports.fileReport(withRefs);
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
