/// Cubit for the home/feed screen.
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/feed_models.dart';
import '../data/feed_repository.dart';

/// Load status for the feed screen.
enum FeedStatus {
  /// Initial — not yet loaded.
  initial,

  /// A request is in flight.
  loading,

  /// Items loaded (possibly an empty list — show the empty state).
  loaded,

  /// The request failed — show retry.
  failure,
}

/// Immutable state for [FeedCubit].
class FeedState {
  /// Creates a state.
  const FeedState({
    this.status = FeedStatus.initial,
    this.items = const [],
    this.error,
  });

  /// The current status.
  final FeedStatus status;

  /// The loaded items.
  final List<FeedItem> items;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  FeedState copyWith({
    FeedStatus? status,
    List<FeedItem>? items,
    Object? error,
  }) => FeedState(
    status: status ?? this.status,
    items: items ?? this.items,
    error: error,
  );
}

/// Loads and refreshes the personalised feed.
class FeedCubit extends Cubit<FeedState> {
  /// Creates the cubit over a [FeedRepository].
  FeedCubit({required FeedRepository repository})
    : _repository = repository,
      super(const FeedState());

  final FeedRepository _repository;

  /// Loads the first page; keeps any previously shown items on failure so the
  /// screen does not blank out when the network drops mid-session.
  Future<void> load() async {
    emit(state.copyWith(status: FeedStatus.loading));
    try {
      final items = await _repository.loadFirstPage();
      emit(FeedState(status: FeedStatus.loaded, items: items));
    } on Object catch (e) {
      emit(state.copyWith(status: FeedStatus.failure, error: e));
    }
  }
}
