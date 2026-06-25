/// Cubit for the PDPA data-request (DSR) self-service screen: list the caller's
/// own requests and open an ACCESS (export) or ERASURE (deletion) request
/// (PRD §18, §25.1; UC-A16/UC-A17).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/dsr_models.dart';
import '../data/dsr_repository.dart';

/// Load status for the DSR screen.
enum DsrStatus {
  /// Not yet loaded.
  initial,

  /// The requests list is loading.
  loading,

  /// Loaded (an empty list means no open requests).
  loaded,

  /// Loading the list failed — show retry.
  failure,
}

/// A one-shot action outcome the UI shows as a banner, then clears.
enum DsrAction {
  /// No banner.
  none,

  /// An ACCESS (export) request was opened.
  accessOpened,

  /// An ERASURE request was opened.
  erasureOpened,
}

/// Immutable state for [DsrCubit].
class DsrState {
  /// Creates a state.
  const DsrState({
    this.status = DsrStatus.initial,
    this.requests = const [],
    this.action = DsrAction.none,
    this.actionInFlight = false,
    this.error,
  });

  /// The list load status.
  final DsrStatus status;

  /// The caller's own tracked requests.
  final List<DataSubjectRequest> requests;

  /// The last one-shot action outcome.
  final DsrAction action;

  /// Whether an open-request write is in flight (disables the buttons).
  final bool actionInFlight;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  DsrState copyWith({
    DsrStatus? status,
    List<DataSubjectRequest>? requests,
    DsrAction? action,
    bool? actionInFlight,
    Object? error,
  }) => DsrState(
    status: status ?? this.status,
    requests: requests ?? this.requests,
    action: action ?? this.action,
    actionInFlight: actionInFlight ?? this.actionInFlight,
    error: error,
  );
}

/// Drives the DSR self-service screen.
class DsrCubit extends Cubit<DsrState> {
  /// Creates the cubit over a [DsrRepository].
  DsrCubit({required DsrRepository repository})
    : _repository = repository,
      super(const DsrState());

  final DsrRepository _repository;

  /// Loads the caller's own open requests.
  Future<void> load() async {
    emit(state.copyWith(status: DsrStatus.loading));
    try {
      final requests = await _repository.listMine();
      emit(DsrState(status: DsrStatus.loaded, requests: requests));
    } on Object catch (e) {
      emit(state.copyWith(status: DsrStatus.failure, error: e));
    }
  }

  /// Opens an ACCESS (data export) request, then refreshes the list.
  Future<void> requestAccess() => _open(
    () => _repository.requestAccess(),
    DsrAction.accessOpened,
  );

  /// Opens an ERASURE (data deletion) request, then refreshes the list.
  ///
  /// A backend block (e.g. the account holds an active staff role) surfaces as
  /// an [error] the UI shows; the list is unchanged.
  Future<void> requestErasure() => _open(
    () => _repository.requestErasure(),
    DsrAction.erasureOpened,
  );

  Future<void> _open(
    Future<DataSubjectRequest> Function() call,
    DsrAction success,
  ) async {
    emit(state.copyWith(actionInFlight: true, action: DsrAction.none));
    try {
      await call();
      final requests = await _repository.listMine();
      emit(
        state.copyWith(
          requests: requests,
          status: DsrStatus.loaded,
          actionInFlight: false,
          action: success,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Clears the one-shot action banner (after the UI shows it).
  void clearAction() => emit(state.copyWith(action: DsrAction.none));
}
