/// Cubit for the notification-preferences screen: list + per-(type,channel)
/// toggle (PRD §13, UC-G08).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/notification_models.dart';
import '../data/notification_repository.dart';

/// Load status for the preferences screen.
enum NotificationPrefsStatus {
  /// Not yet loaded.
  initial,

  /// A request is in flight.
  loading,

  /// Loaded (an empty list means defaults apply).
  loaded,

  /// Failed — show retry.
  failure,
}

/// Immutable state for [NotificationPrefsCubit].
class NotificationPrefsState {
  /// Creates a state.
  const NotificationPrefsState({
    this.status = NotificationPrefsStatus.initial,
    this.prefs = const [],
    this.savingId,
    this.error,
  });

  /// The current status.
  final NotificationPrefsStatus status;

  /// The loaded preferences.
  final List<NotificationPreference> prefs;

  /// The id of the preference currently being saved, or `null`.
  final String? savingId;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  NotificationPrefsState copyWith({
    NotificationPrefsStatus? status,
    List<NotificationPreference>? prefs,
    String? savingId,
    bool clearSaving = false,
    Object? error,
  }) => NotificationPrefsState(
    status: status ?? this.status,
    prefs: prefs ?? this.prefs,
    savingId: clearSaving ? null : (savingId ?? this.savingId),
    error: error,
  );
}

/// Loads and toggles notification preferences.
class NotificationPrefsCubit extends Cubit<NotificationPrefsState> {
  /// Creates the cubit over a [NotificationRepository].
  NotificationPrefsCubit({required NotificationRepository repository})
    : _repository = repository,
      super(const NotificationPrefsState());

  final NotificationRepository _repository;

  /// Loads the caller's preferences.
  Future<void> load() async {
    emit(state.copyWith(status: NotificationPrefsStatus.loading));
    try {
      final prefs = await _repository.listPreferences();
      emit(
        NotificationPrefsState(
          status: NotificationPrefsStatus.loaded,
          prefs: prefs,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(status: NotificationPrefsStatus.failure, error: e));
    }
  }

  /// Toggles one `(type, channel)` preference and updates it in place.
  ///
  /// A backend rejection (e.g. an always-on type) surfaces as an error the UI
  /// shows; the optimistic value is reverted by reloading.
  Future<void> toggle(NotificationPreference pref, bool enabled) async {
    emit(state.copyWith(savingId: pref.id));
    try {
      final updated = await _repository.upsertPreference(
        type: pref.type,
        channel: pref.channel,
        enabled: enabled,
        language: pref.language,
      );
      final prefs = state.prefs
          .map((p) => p.id == updated.id ? updated : p)
          .toList(growable: false);
      emit(state.copyWith(prefs: prefs, clearSaving: true));
    } on Object catch (e) {
      emit(state.copyWith(clearSaving: true, error: e));
    }
  }
}
