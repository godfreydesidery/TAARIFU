/// Cubit for the discovery/search screen: a debounced keyword search with an
/// optional kind filter (PRD discovery; ADR-0017).
///
/// WHY debounced in the cubit (not the widget): the citizen is on a tiny data
/// bundle over 2G — firing a request on every keystroke would burn data and
/// money (PRD §15). We coalesce typing into one call ~350ms after the last key,
/// and a blank query resolves to the empty initial state with no call at all.
library;

import 'dart:async';

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/search_models.dart';
import '../data/search_repository.dart';

/// The search screen's load status.
enum SearchStatus {
  /// No query yet — show the idle/hint state.
  idle,

  /// A search is in flight (or debouncing toward one).
  loading,

  /// Results are loaded (possibly empty → "no matches").
  loaded,

  /// The search failed — show retry.
  failure,
}

/// Immutable state for [SearchCubit].
class SearchState {
  /// Creates a state.
  const SearchState({
    this.status = SearchStatus.idle,
    this.query = '',
    this.kind,
    this.results = const [],
    this.error,
  });

  /// The current status.
  final SearchStatus status;

  /// The active query text (trimmed at search time).
  final String query;

  /// The active kind filter, or `null` for all kinds.
  final SearchResultKind? kind;

  /// The current results, most-relevant first.
  final List<SearchResult> results;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides. [clearKind] removes the kind filter (since
  /// `null` is its meaningful "all kinds" value, a plain null cannot express it).
  SearchState copyWith({
    SearchStatus? status,
    String? query,
    SearchResultKind? kind,
    bool clearKind = false,
    List<SearchResult>? results,
    Object? error,
  }) => SearchState(
    status: status ?? this.status,
    query: query ?? this.query,
    kind: clearKind ? null : (kind ?? this.kind),
    results: results ?? this.results,
    error: error,
  );
}

/// Drives the discovery search with debounced queries.
class SearchCubit extends Cubit<SearchState> {
  /// Creates the cubit over a [SearchRepository]; [debounce] is overridable for
  /// tests (so a test need not wait the real 350ms).
  SearchCubit({
    required SearchRepository repository,
    Duration debounce = const Duration(milliseconds: 350),
  }) : _repository = repository,
       _debounce = debounce,
       super(const SearchState());

  final SearchRepository _repository;
  final Duration _debounce;
  Timer? _debounceTimer;

  /// Updates the query and schedules a debounced search.
  ///
  /// A blank query cancels any pending search and returns to the idle state with
  /// no network call (data-frugal, PRD §15).
  void queryChanged(String query) {
    _debounceTimer?.cancel();
    final trimmed = query.trim();
    if (trimmed.isEmpty) {
      emit(
        state.copyWith(
          status: SearchStatus.idle,
          query: '',
          results: const [],
        ),
      );
      return;
    }
    emit(state.copyWith(status: SearchStatus.loading, query: query));
    _debounceTimer = Timer(_debounce, () => _run(query, state.kind));
  }

  /// Sets (or clears, when [kind] is `null`) the kind filter and re-runs the
  /// current query immediately (the citizen has already paid for these keys).
  void setKind(SearchResultKind? kind) {
    emit(state.copyWith(kind: kind, clearKind: kind == null));
    if (state.query.trim().isEmpty) return;
    _debounceTimer?.cancel();
    emit(state.copyWith(status: SearchStatus.loading));
    _run(state.query, kind);
  }

  /// Retries the current query+filter (the error-state retry button).
  void retry() {
    if (state.query.trim().isEmpty) return;
    emit(state.copyWith(status: SearchStatus.loading));
    _run(state.query, state.kind);
  }

  Future<void> _run(String query, SearchResultKind? kind) async {
    try {
      final results = await _repository.search(query, kind: kind);
      // Ignore a stale response if the query changed while in flight.
      if (query.trim() != state.query.trim()) return;
      emit(state.copyWith(status: SearchStatus.loaded, results: results));
    } on Object catch (e) {
      if (query.trim() != state.query.trim()) return;
      emit(state.copyWith(status: SearchStatus.failure, error: e));
    }
  }

  @override
  Future<void> close() {
    _debounceTimer?.cancel();
    return super.close();
  }
}
