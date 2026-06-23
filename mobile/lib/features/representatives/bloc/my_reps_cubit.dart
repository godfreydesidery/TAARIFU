/// Cubit for the "find my representatives" screen.
///
/// WHY a Cubit (not a full Bloc): the flow is a simple imperative request —
/// "given a ward id, load the rep bundle" — with no rich event stream, so a
/// Cubit is the simplest thing that works (KISS, CLAUDE.md §3).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/representative_models.dart';
import '../data/representative_repository.dart';

/// Load status for the find-my-rep screen.
enum MyRepsStatus {
  /// No ward chosen yet — show the ward picker.
  idle,

  /// A request is in flight.
  loading,

  /// The bundle loaded (it may legitimately contain empty slots).
  loaded,

  /// The request failed (offline/timeout/server) — show retry.
  failure,
}

/// Immutable state for [MyRepsCubit].
class MyRepsState {
  /// Creates a state.
  const MyRepsState({
    this.status = MyRepsStatus.idle,
    this.data,
    this.error,
  });

  /// The current status.
  final MyRepsStatus status;

  /// The loaded bundle, or `null`.
  final MyRepresentatives? data;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  MyRepsState copyWith({
    MyRepsStatus? status,
    MyRepresentatives? data,
    Object? error,
  }) => MyRepsState(
    status: status ?? this.status,
    data: data ?? this.data,
    error: error,
  );
}

/// Loads representatives for a chosen ward.
class MyRepsCubit extends Cubit<MyRepsState> {
  /// Creates the cubit over a [RepresentativeRepository].
  MyRepsCubit({required RepresentativeRepository repository})
    : _repository = repository,
      super(const MyRepsState());

  final RepresentativeRepository _repository;

  /// Loads the rep bundle for [wardId] (e.g. from GPS resolve or manual pick).
  Future<void> loadForWard(String wardId) async {
    emit(const MyRepsState(status: MyRepsStatus.loading));
    try {
      final reps = await _repository.findByWard(wardId);
      emit(MyRepsState(status: MyRepsStatus.loaded, data: reps));
    } on Object catch (e) {
      emit(MyRepsState(status: MyRepsStatus.failure, error: e));
    }
  }
}
