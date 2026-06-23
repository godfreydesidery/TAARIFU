/// Cubit for the notification inbox: list + mark-read (PRD §13, UC-G09).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/notification_models.dart';
import '../data/notification_repository.dart';

/// Load status for the inbox.
enum NotificationsStatus {
  /// Not yet loaded.
  initial,

  /// A request is in flight.
  loading,

  /// Loaded (possibly empty).
  loaded,

  /// Failed — show retry.
  failure,
}

/// Immutable state for [NotificationsCubit].
class NotificationsState {
  /// Creates a state.
  const NotificationsState({
    this.status = NotificationsStatus.initial,
    this.items = const [],
    this.error,
  });

  /// The current status.
  final NotificationsStatus status;

  /// The loaded notifications, newest first.
  final List<AppNotification> items;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  NotificationsState copyWith({
    NotificationsStatus? status,
    List<AppNotification>? items,
    Object? error,
  }) => NotificationsState(
    status: status ?? this.status,
    items: items ?? this.items,
    error: error,
  );
}

/// Loads notifications and marks them read.
class NotificationsCubit extends Cubit<NotificationsState> {
  /// Creates the cubit over a [NotificationRepository].
  NotificationsCubit({required NotificationRepository repository})
    : _repository = repository,
      super(const NotificationsState());

  final NotificationRepository _repository;

  /// Loads the inbox (cached on a network miss).
  Future<void> load() async {
    emit(state.copyWith(status: NotificationsStatus.loading));
    try {
      final items = await _repository.listMine();
      emit(NotificationsState(status: NotificationsStatus.loaded, items: items));
    } on Object catch (e) {
      emit(state.copyWith(status: NotificationsStatus.failure, error: e));
    }
  }

  /// Marks one notification read and updates it in place.
  Future<void> markRead(String id) async {
    try {
      final updated = await _repository.markRead(id);
      final items = state.items
          .map((n) => n.id == updated.id ? updated : n)
          .toList(growable: false);
      emit(state.copyWith(items: items));
    } on Object catch (e) {
      emit(state.copyWith(error: e));
    }
  }
}
