/// Cubit for the engagement hub: loads petitions, surveys, and Q&A, surfaces the
/// caller's tier for client-side gating, and performs the sign/respond/ask writes.
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../../profile/data/profile_repository.dart';
import '../data/engagement_models.dart';
import '../data/engagement_repository.dart';

/// Per-list load status.
enum EngagementListStatus {
  /// Not yet loaded.
  initial,

  /// A request is in flight.
  loading,

  /// Loaded (possibly empty).
  loaded,

  /// Failed — show retry.
  failure,
}

/// A one-shot action result the UI shows as a banner, then clears.
enum EngagementAction {
  /// No banner.
  none,

  /// A petition was signed.
  signed,

  /// A survey/poll was responded to.
  responded,

  /// A question was asked.
  asked,

  /// The action needs a higher tier than the caller has.
  tierTooLow,
}

/// Immutable state for [EngagementCubit].
class EngagementState {
  /// Creates a state.
  const EngagementState({
    this.petitionsStatus = EngagementListStatus.initial,
    this.surveysStatus = EngagementListStatus.initial,
    this.questionsStatus = EngagementListStatus.initial,
    this.petitions = const [],
    this.surveys = const [],
    this.questions = const [],
    this.tier = 'T1',
    this.action = EngagementAction.none,
    this.actionInFlight = false,
    this.error,
  });

  /// Petitions list status.
  final EngagementListStatus petitionsStatus;

  /// Surveys list status.
  final EngagementListStatus surveysStatus;

  /// Questions list status.
  final EngagementListStatus questionsStatus;

  /// Loaded petitions.
  final List<Petition> petitions;

  /// Loaded surveys/polls.
  final List<Survey> surveys;

  /// Loaded questions.
  final List<Question> questions;

  /// The caller's live tier (for client-side gating hints).
  final String tier;

  /// The last one-shot action outcome.
  final EngagementAction action;

  /// Whether a write action is in flight.
  final bool actionInFlight;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// True if the tier is at least T2.
  bool get isT2OrAbove => _rank(tier) >= 2;

  /// True if the tier is T3.
  bool get isT3 => _rank(tier) >= 3;

  static int _rank(String t) => switch (t) {
    'T3' => 3,
    'T2' => 2,
    'T1' => 1,
    _ => 0,
  };

  /// Returns a copy with overrides.
  EngagementState copyWith({
    EngagementListStatus? petitionsStatus,
    EngagementListStatus? surveysStatus,
    EngagementListStatus? questionsStatus,
    List<Petition>? petitions,
    List<Survey>? surveys,
    List<Question>? questions,
    String? tier,
    EngagementAction? action,
    bool? actionInFlight,
    Object? error,
  }) => EngagementState(
    petitionsStatus: petitionsStatus ?? this.petitionsStatus,
    surveysStatus: surveysStatus ?? this.surveysStatus,
    questionsStatus: questionsStatus ?? this.questionsStatus,
    petitions: petitions ?? this.petitions,
    surveys: surveys ?? this.surveys,
    questions: questions ?? this.questions,
    tier: tier ?? this.tier,
    action: action ?? this.action,
    actionInFlight: actionInFlight ?? this.actionInFlight,
    error: error,
  );
}

/// Drives the engagement hub.
class EngagementCubit extends Cubit<EngagementState> {
  /// Creates the cubit over the engagement + profile repositories.
  EngagementCubit({
    required EngagementRepository repository,
    required ProfileRepository profileRepository,
  }) : _repository = repository,
       _profile = profileRepository,
       super(const EngagementState());

  final EngagementRepository _repository;
  final ProfileRepository _profile;

  /// Loads the tier (best-effort) and all three lists.
  Future<void> loadAll() async {
    // Tier is a best-effort UX hint; a failure must not block browsing.
    try {
      final tier = await _profile.getTier();
      emit(state.copyWith(tier: tier));
    } on Object {
      // Keep the default tier; the server still enforces gates on write.
    }
    await Future.wait([loadPetitions(), loadSurveys(), loadQuestions()]);
  }

  /// Loads public petitions.
  Future<void> loadPetitions() async {
    emit(state.copyWith(petitionsStatus: EngagementListStatus.loading));
    try {
      final items = await _repository.listPetitions();
      emit(
        state.copyWith(
          petitions: items,
          petitionsStatus: EngagementListStatus.loaded,
        ),
      );
    } on Object catch (e) {
      emit(
        state.copyWith(
          petitionsStatus: EngagementListStatus.failure,
          error: e,
        ),
      );
    }
  }

  /// Loads public surveys/polls.
  Future<void> loadSurveys() async {
    emit(state.copyWith(surveysStatus: EngagementListStatus.loading));
    try {
      final items = await _repository.listSurveys();
      emit(
        state.copyWith(
          surveys: items,
          surveysStatus: EngagementListStatus.loaded,
        ),
      );
    } on Object catch (e) {
      emit(
        state.copyWith(surveysStatus: EngagementListStatus.failure, error: e),
      );
    }
  }

  /// Loads public Q&A.
  Future<void> loadQuestions() async {
    emit(state.copyWith(questionsStatus: EngagementListStatus.loading));
    try {
      final items = await _repository.listQuestions();
      emit(
        state.copyWith(
          questions: items,
          questionsStatus: EngagementListStatus.loaded,
        ),
      );
    } on Object catch (e) {
      emit(
        state.copyWith(
          questionsStatus: EngagementListStatus.failure,
          error: e,
        ),
      );
    }
  }

  /// Signs a petition (T3). Surfaces a tier gate up front if below T3.
  Future<void> signPetition(
    String petitionId, {
    String? comment,
    bool publicSignature = false,
  }) async {
    if (!state.isT3) {
      emit(state.copyWith(action: EngagementAction.tierTooLow));
      return;
    }
    emit(state.copyWith(actionInFlight: true, action: EngagementAction.none));
    try {
      final updated = await _repository.signPetition(
        petitionId,
        comment: comment,
        publicSignature: publicSignature,
      );
      final list = state.petitions
          .map((p) => p.id == updated.id ? updated : p)
          .toList(growable: false);
      emit(
        state.copyWith(
          petitions: list,
          actionInFlight: false,
          action: EngagementAction.signed,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Responds to a survey/poll (T3). Surfaces a tier gate up front if below T3.
  Future<void> respondToSurvey(String surveyId, {required String answers}) async {
    if (!state.isT3) {
      emit(state.copyWith(action: EngagementAction.tierTooLow));
      return;
    }
    emit(state.copyWith(actionInFlight: true, action: EngagementAction.none));
    try {
      await _repository.respondToSurvey(surveyId, answers: answers);
      emit(
        state.copyWith(
          actionInFlight: false,
          action: EngagementAction.responded,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Asks a representative a question (T2). Surfaces a tier gate if below T2.
  Future<void> askQuestion({
    required String targetRepId,
    required String body,
  }) async {
    if (!state.isT2OrAbove) {
      emit(state.copyWith(action: EngagementAction.tierTooLow));
      return;
    }
    emit(state.copyWith(actionInFlight: true, action: EngagementAction.none));
    try {
      await _repository.askQuestion(targetRepId: targetRepId, body: body);
      await loadQuestions();
      emit(state.copyWith(actionInFlight: false, action: EngagementAction.asked));
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Clears the one-shot action banner (after the UI shows it).
  void clearAction() => emit(state.copyWith(action: EngagementAction.none));
}
