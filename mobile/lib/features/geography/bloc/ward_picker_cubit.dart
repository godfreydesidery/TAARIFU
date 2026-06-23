/// Cubit driving the **manual ward picker** — the shared flow that lets a citizen
/// choose a ward (Kata) without typing a UUID, used by the report form, profile
/// locations, and find-my-representatives (PRD §9.0, §22.6).
///
/// Two ways to land on a ward, both data-frugal (PRD §15):
///   * **Search** — type a ward name; we query `GET /wards?q=` (district-scoped when
///     a district is chosen). A blank box shows nothing rather than pulling the
///     national ward table.
///   * **Browse** — pick Region (Mkoa) → District (Wilaya) → ward, backed by the
///     cached `GET /districts/{id}/wards` listing so browsing works offline once a
///     district has been opened.
///
/// WHY a Cubit (not a full Bloc): the flow is a sequence of simple imperative
/// requests with no rich event stream (KISS, CLAUDE.md §3). Selection is returned to
/// the caller by the screen (via Navigator) — this cubit owns lookup, not the
/// downstream binding.
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import '../data/geography_models.dart';
import '../data/geography_repository.dart';

/// What the ward list currently reflects.
enum WardListMode {
  /// Wards listed by browsing into a district.
  browse,

  /// Wards matching a name search.
  search,
}

/// Load status for the ward list portion of the picker.
enum WardListStatus {
  /// Nothing requested yet (no district opened, empty search box).
  idle,

  /// A ward list/search request is in flight.
  loading,

  /// Wards loaded (the list may legitimately be empty → show the empty state).
  loaded,

  /// The list/search request failed — show retry.
  failure,
}

/// Immutable state for [WardPickerCubit].
class WardPickerState {
  /// Creates a state.
  const WardPickerState({
    this.regions = const [],
    this.regionsLoading = false,
    this.districts = const [],
    this.districtsLoading = false,
    this.selectedRegionId,
    this.selectedDistrictId,
    this.wards = const [],
    this.wardStatus = WardListStatus.idle,
    this.mode = WardListMode.browse,
    this.query = '',
    this.error,
  });

  /// Regions (Mikoa) for the browse path.
  final List<Region> regions;

  /// Whether the regions list is loading.
  final bool regionsLoading;

  /// Districts (Wilaya) of [selectedRegionId].
  final List<District> districts;

  /// Whether the districts list is loading.
  final bool districtsLoading;

  /// The chosen region's id, or `null`.
  final String? selectedRegionId;

  /// The chosen district's id, or `null`.
  final String? selectedDistrictId;

  /// The current ward list (browse listing or search results).
  final List<WardSummary> wards;

  /// The ward list's load status.
  final WardListStatus wardStatus;

  /// Whether [wards] reflects a browse listing or a search.
  final WardListMode mode;

  /// The latest search text (for the search box + scoping).
  final String query;

  /// The caught failure to localise, or `null`.
  final Object? error;

  /// Returns a copy with overrides. [clearError] resets [error] to `null`.
  WardPickerState copyWith({
    List<Region>? regions,
    bool? regionsLoading,
    List<District>? districts,
    bool? districtsLoading,
    String? selectedRegionId,
    String? selectedDistrictId,
    bool clearDistrict = false,
    List<WardSummary>? wards,
    WardListStatus? wardStatus,
    WardListMode? mode,
    String? query,
    Object? error,
    bool clearError = false,
  }) => WardPickerState(
    regions: regions ?? this.regions,
    regionsLoading: regionsLoading ?? this.regionsLoading,
    districts: districts ?? this.districts,
    districtsLoading: districtsLoading ?? this.districtsLoading,
    selectedRegionId: selectedRegionId ?? this.selectedRegionId,
    selectedDistrictId: clearDistrict
        ? null
        : (selectedDistrictId ?? this.selectedDistrictId),
    wards: wards ?? this.wards,
    wardStatus: wardStatus ?? this.wardStatus,
    mode: mode ?? this.mode,
    query: query ?? this.query,
    error: clearError ? null : (error ?? this.error),
  );
}

/// Loads regions/districts/wards for the manual ward picker.
class WardPickerCubit extends Cubit<WardPickerState> {
  /// Creates the cubit over a [GeographyRepository].
  WardPickerCubit({required GeographyRepository repository})
    : _repository = repository,
      super(const WardPickerState());

  final GeographyRepository _repository;

  /// Loads the regions list for the browse path (cached, offline-friendly).
  Future<void> loadRegions() async {
    emit(state.copyWith(regionsLoading: true, clearError: true));
    try {
      final regions = await _repository.listRegions();
      emit(state.copyWith(regions: regions, regionsLoading: false));
    } on Object catch (e) {
      emit(state.copyWith(regionsLoading: false, error: e));
    }
  }

  /// Selects a region and loads its districts; resets the downstream district +
  /// ward selection so the picker can't show stale children.
  Future<void> selectRegion(String regionId) async {
    emit(
      state.copyWith(
        selectedRegionId: regionId,
        clearDistrict: true,
        districts: const [],
        districtsLoading: true,
        wards: const [],
        wardStatus: WardListStatus.idle,
        clearError: true,
      ),
    );
    try {
      final districts = await _repository.listDistricts(regionId);
      emit(state.copyWith(districts: districts, districtsLoading: false));
    } on Object catch (e) {
      emit(state.copyWith(districtsLoading: false, error: e));
    }
  }

  /// Selects a district and lists its wards (browse mode).
  Future<void> selectDistrict(String districtId) async {
    emit(
      state.copyWith(
        selectedDistrictId: districtId,
        mode: WardListMode.browse,
        wardStatus: WardListStatus.loading,
        wards: const [],
        clearError: true,
      ),
    );
    try {
      final wards = await _repository.listWardsInDistrict(districtId);
      emit(
        state.copyWith(wards: wards, wardStatus: WardListStatus.loaded),
      );
    } on Object catch (e) {
      emit(state.copyWith(wardStatus: WardListStatus.failure, error: e));
    }
  }

  /// Runs a ward name search (search mode), scoped to the chosen district if any.
  ///
  /// A blank [query] returns to the idle/browse state without a call (data-frugal).
  Future<void> search(String query) async {
    final q = query.trim();
    emit(state.copyWith(query: query, mode: WardListMode.search));
    if (q.isEmpty) {
      emit(
        state.copyWith(wards: const [], wardStatus: WardListStatus.idle),
      );
      return;
    }
    emit(state.copyWith(wardStatus: WardListStatus.loading, clearError: true));
    try {
      final wards = await _repository.searchWards(
        q,
        districtId: state.selectedDistrictId,
      );
      // Guard against an out-of-order response overwriting a newer query.
      if (state.query.trim() != q) {
        return;
      }
      emit(
        state.copyWith(wards: wards, wardStatus: WardListStatus.loaded),
      );
    } on Object catch (e) {
      emit(state.copyWith(wardStatus: WardListStatus.failure, error: e));
    }
  }

  /// Re-runs whichever load (browse listing or search) currently applies — the
  /// retry action on the error state.
  Future<void> retry() async {
    if (state.mode == WardListMode.search) {
      await search(state.query);
    } else if (state.selectedDistrictId != null) {
      await selectDistrict(state.selectedDistrictId!);
    }
  }
}
