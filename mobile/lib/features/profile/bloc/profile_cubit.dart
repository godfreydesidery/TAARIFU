/// Cubit for the profile + verification screen: load `me`, complete the profile
/// (→T2), add a location, and submit ID/voter verification (→T3).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/profile_models.dart';
import '../data/profile_repository.dart';

/// Load status for the profile screen.
enum ProfileStatus {
  /// Initial — not yet loaded.
  initial,

  /// A load is in flight.
  loading,

  /// The snapshot loaded.
  loaded,

  /// The load failed — show retry.
  failure,
}

/// Transient outcome of the last write action, surfaced as a one-shot banner.
enum ProfileAction {
  /// No pending action banner.
  none,

  /// The profile was saved (tier may have changed).
  profileSaved,

  /// A location was added.
  locationAdded,

  /// A verification request was submitted (PENDING).
  verificationSubmitted,
}

/// Immutable state for [ProfileCubit].
class ProfileState {
  /// Creates a state.
  const ProfileState({
    this.status = ProfileStatus.initial,
    this.me,
    this.action = ProfileAction.none,
    this.actionInFlight = false,
    this.error,
  });

  /// The current status.
  final ProfileStatus status;

  /// The loaded snapshot, or `null`.
  final Me? me;

  /// The last write outcome to surface once, then clear.
  final ProfileAction action;

  /// Whether a write action is in flight.
  final bool actionInFlight;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides.
  ProfileState copyWith({
    ProfileStatus? status,
    Me? me,
    ProfileAction? action,
    bool? actionInFlight,
    Object? error,
  }) => ProfileState(
    status: status ?? this.status,
    me: me ?? this.me,
    action: action ?? this.action,
    actionInFlight: actionInFlight ?? this.actionInFlight,
    error: error,
  );
}

/// Loads `me` and performs the profile/location/verification writes.
class ProfileCubit extends Cubit<ProfileState> {
  /// Creates the cubit over a [ProfileRepository].
  ProfileCubit({required ProfileRepository repository})
    : _repository = repository,
      super(const ProfileState());

  final ProfileRepository _repository;

  /// Loads the caller's profile snapshot.
  Future<void> load() async {
    emit(state.copyWith(status: ProfileStatus.loading));
    try {
      final me = await _repository.getMe();
      emit(ProfileState(status: ProfileStatus.loaded, me: me));
    } on Object catch (e) {
      emit(state.copyWith(status: ProfileStatus.failure, error: e));
    }
  }

  /// Saves first/last name; may promote to T2. Reloads the snapshot after.
  Future<void> completeProfile({
    required String firstName,
    required String lastName,
  }) async {
    emit(state.copyWith(actionInFlight: true, action: ProfileAction.none));
    try {
      await _repository.updateProfile(
        firstName: firstName,
        lastName: lastName,
      );
      final me = await _repository.getMe();
      emit(
        ProfileState(
          status: ProfileStatus.loaded,
          me: me,
          action: ProfileAction.profileSaved,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Adds a ward location (≥1-pin half of T2). Reloads after.
  Future<void> addLocation({
    required String wardPublicId,
    required String associationType,
    bool primary = false,
  }) async {
    emit(state.copyWith(actionInFlight: true, action: ProfileAction.none));
    try {
      await _repository.addLocation(
        wardPublicId: wardPublicId,
        associationType: associationType,
        primary: primary,
      );
      final me = await _repository.getMe();
      emit(
        ProfileState(
          status: ProfileStatus.loaded,
          me: me,
          action: ProfileAction.locationAdded,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }

  /// Submits ID/voter verification (→ PENDING). Reloads after.
  Future<void> submitVerification({
    required String idType,
    required String idNo,
    required String fullName,
  }) async {
    emit(state.copyWith(actionInFlight: true, action: ProfileAction.none));
    try {
      await _repository.submitVerification(
        idType: idType,
        idNo: idNo,
        fullName: fullName,
      );
      final me = await _repository.getMe();
      emit(
        ProfileState(
          status: ProfileStatus.loaded,
          me: me,
          action: ProfileAction.verificationSubmitted,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(actionInFlight: false, error: e));
    }
  }
}
