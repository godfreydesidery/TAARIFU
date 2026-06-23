/// Cubit for the announcement-detail screen: fetches the full announcement body
/// for the [FeedItem] the citizen tapped (US-4.2, `GET /announcements/{id}`).
///
/// WHY a Cubit (not a full Bloc): it is one imperative request — "given an id, load
/// the announcement" — with no event stream (KISS, CLAUDE.md §3). The screen starts
/// from the lean [FeedItem] already in hand, so even before/without the network it
/// can render the title + snippet; this cubit upgrades that to the full body when
/// the fetch (or its cache) succeeds, and surfaces a non-blocking retry otherwise.
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/feed_models.dart';
import '../data/feed_repository.dart';

/// Load status for the announcement detail.
enum AnnouncementDetailStatus {
  /// Initial — not yet loaded.
  initial,

  /// A request is in flight.
  loading,

  /// The full announcement loaded (from network or cache).
  loaded,

  /// The fetch failed — the screen still shows the feed snippet + a retry.
  failure,
}

/// Immutable state for [AnnouncementDetailCubit].
class AnnouncementDetailState {
  /// Creates a state.
  const AnnouncementDetailState({
    this.status = AnnouncementDetailStatus.initial,
    this.announcement,
    this.error,
  });

  /// The current status.
  final AnnouncementDetailStatus status;

  /// The loaded full announcement, or `null` until [loaded].
  final Announcement? announcement;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  AnnouncementDetailState copyWith({
    AnnouncementDetailStatus? status,
    Announcement? announcement,
    Object? error,
  }) => AnnouncementDetailState(
    status: status ?? this.status,
    announcement: announcement ?? this.announcement,
    error: error,
  );
}

/// Loads one announcement's full body by id.
class AnnouncementDetailCubit extends Cubit<AnnouncementDetailState> {
  /// Creates the cubit over a [FeedRepository] for the given [announcementId].
  AnnouncementDetailCubit({
    required FeedRepository repository,
    required this.announcementId,
  }) : _repository = repository,
       super(const AnnouncementDetailState());

  final FeedRepository _repository;

  /// The announcement public id to load (the tapped feed item's id).
  final String announcementId;

  /// Loads the full announcement (network → cache fallback in the repository).
  Future<void> load() async {
    emit(state.copyWith(status: AnnouncementDetailStatus.loading));
    try {
      final announcement = await _repository.getAnnouncement(announcementId);
      emit(
        AnnouncementDetailState(
          status: AnnouncementDetailStatus.loaded,
          announcement: announcement,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(status: AnnouncementDetailStatus.failure, error: e));
    }
  }
}
