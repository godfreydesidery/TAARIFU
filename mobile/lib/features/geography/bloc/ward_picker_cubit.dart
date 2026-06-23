/// Cubit driving the **manual ward picker** (Chagua kata) — the GPS-free way a
/// citizen pins a ward (Kata) for the report form, profile locations, and
/// find-my-rep, replacing the hand-typed ward UUID.
///
/// Two interchangeable paths over the new public geography endpoints:
///   * **Search** — type a ward name → `GET /wards?q=&districtId=` (the fast
///     path when the citizen knows the ward name).
///   * **Drill-down** — Region (Mkoa) → District (Wilaya) → ward list, via
///     `GET /regions`, `GET /regions/{id}/districts`, `GET /districts/{id}/wards`
///     (the path when they orient by geography). The district listing is cached
///     so it works offline (PRD §15).
///
/// WHY a Cubit (not a Bloc): the flow is a sequence of imperative loads with no
/// rich event stream, so a Cubit is the simplest thing that works (KISS).
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/geography_models.dart';
import '../data/geography_repository.dart';

/// Which input mode the picker is showing.
enum WardPickerMode {
  /// Drill Region → District → ward list.
  browse,

  /// Type a ward name and search.
  search,
}

/// Load phase of the active list (regions/districts/wards/results).
enum WardPickerStatus {
  /// Idle — nothing loading.
  idle,

  /// A list request is in flight.
  loading,

  /// The active list loaded.
  loaded,

  /// The active list failed — show retry.
  failure,
}

/// Immutable state for [WardPickerCubit].
class WardPickerState {
  /// Creates a state.
  const WardPickerState({
    this.mode = WardPickerMode.browse,
    this.status = WardPickerStatus.idle,
    this.regions = const [],
    this.districts = const [],
    this.wards = const [],
    this.results = const [],
    this.selectedRegion,
    this.selectedDistrict,
    this.searching = false,
    this.error,
  });

  /// Active input mode (browse vs search).
  final WardPickerMode mode;

  /// Load status of the active list.
  final WardPickerStatus status;

  /// Loaded regions (Mikoa) for the drill-down's first step.
  final List<Region> regions;

  /// Districts (Wilaya) of [selectedRegion].
  final List<District> districts;

  /// Wards (Kata) of [selectedDistrict] — the browse-mode pick list.
  final List<WardSummary> wards;

  /// Wards matching the search query — the search-mode pick list.
  final List<WardSummary> results;

  /// The region the citizen drilled into, or `null`.
  final Region? selectedRegion;

  /// The district the citizen drilled into, or `null`.
  final District? selectedDistrict;

  /// Whether a search request is currently in flight (debounced typing).
  final bool searching;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides. [error] is cleared unless re-supplied.
  WardPickerState copyWith({
    WardPickerMode? mode,
    WardPickerStatus? status,
    List<Region>? regions,
    List<District>? districts,
    List<WardSummary>? wards,
    List<WardSummary>? results,
    Region? selectedRegion,
    District? selectedDistrict,
    bool clearRegion = false,
    bool clearDistrict = false,
    bool? searching,
    Object? error,
  }) => WardPickerState(
    mode: mode ?? this.mode,
    status: status ?? this.status,
    regions: regions ?? this.regions,
    districts: districts ?? this.districts,
    wards: wards ?? this.wards,
    results: results ?? this.results,
    selectedRegion: clearRegion ? null : (selectedRegion ?? this.selectedRegion),
    selectedDistrict:
        clearDistrict ? null : (selectedDistrict ?? this.selectedDistrict),
    searching: searching ?? this.searching,
    error: error,
  );
}

/// Loads regions/districts/wards and ward-name search results for the picker.
class WardPickerCubit extends Cubit<WardPickerState> {
  /// Creates the cubit over a [GeographyRepository].
  WardPickerCubit({required GeographyRepository repository})
    : _repository = repository,
      super(const WardPickerState());

  final GeographyRepository _repository;

  /// Loads the region list (the drill-down's first step). Tolerant of offline
  /// (regions are served from cache by the repository).
  Future<void> loadRegions() async {
    emit(state.copyWith(status: WardPickerStatus.loading));
    try {
      final regions = await _repository.listRegions();
      emit(
        state.copyWith(status: WardPickerStatus.loaded, regions: regions),
      );
    } on Object catch (e) {
      emit(state.copyWith(status: WardPickerStatus.failure, error: e));
    }
  }

  /// Switches between browse and search modes (clears the other mode's noise).
  void setMode(WardPickerMode mode) {
    if (mode == state.mode) return;
    emit(
      state.copyWith(
        mode: mode,
        status: mode == WardPickerMode.browse && state.regions.isEmpty
            ? WardPickerStatus.idle
            : WardPickerStatus.loaded,
      ),
    );
    if (mode == WardPickerMode.browse && state.regions.isEmpty) {
      loadRegions();
    }
  }

  /// Drills into [region]: loads its districts and resets the ward list.
  Future<void> selectRegion(Region region) async {
    emit(
      state.copyWith(
        status: WardPickerStatus.loading,
        selectedRegion: region,
        clearDistrict: true,
        districts: const [],
        wards: const [],
      ),
    );
    try {
      final districts = await _repository.listDistricts(region.id);
      emit(
        state.copyWith(
          status: WardPickerStatus.loaded,
          districts: districts,
        ),
      );
    } on Object catch (e) {
      emit(state.copyWith(status: WardPickerStatus.failure, error: e));
    }
  }

  /// Drills into [district]: loads its wards (the browse-mode pick list).
  Future<void> selectDistrict(District district) async {
    emit(
      state.copyWith(
        status: WardPickerStatus.loading,
        selectedDistrict: district,
        wards: const [],
      ),
    );
    try {
      final wards = await _repository.listWardsInDistrict(district.id);
      emit(
        state.copyWith(status: WardPickerStatus.loaded, wards: wards),
      );
    } on Object catch (e) {
      emit(state.copyWith(status: WardPickerStatus.failure, error: e));
    }
  }

  /// Searches wards by [query]. A blank query clears the results without a call.
  ///
  /// The search is scoped to the drilled-into district when one is selected, so
  /// "find my representative" / report-form searches narrow naturally.
  Future<void> search(String query) async {
    final q = query.trim();
    if (q.isEmpty) {
      emit(state.copyWith(results: const [], searching: false));
      return;
    }
    emit(state.copyWith(searching: true));
    try {
      final results = await _repository.searchWards(
        q,
        districtId: state.selectedDistrict?.id,
      );
      emit(state.copyWith(results: results, searching: false));
    } on Object catch (e) {
      // A search failure is non-fatal — keep the box usable; surface the error.
      emit(state.copyWith(searching: false, error: e));
    }
  }
}
