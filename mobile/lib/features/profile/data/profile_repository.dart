/// Repository for the identity/profile flows: read `me`, complete the profile
/// (→T2), manage locations (primary/electoral), and submit ID/voter verification
/// (→T3) — PRD §10 Epic M0 (US-0.2, US-0.3, US-0.8).
///
/// Responsibility: translate these use-cases into [ApiClient] calls against the
/// real `ProfileController`, `ProfileLocationController`, and `Verification
/// Controller`. The `me` snapshot is the source of the live trust tier the app
/// uses to gate T2/T3 actions client-side (a UX hint; the server re-checks).
///
/// Locations and `idNo` are private PII — none of these reads ever exposes
/// another citizen's data, and `idNo` is write-only (never echoed) by design
/// (PRD §18).
library;

import '../../../core/network/api_client.dart';
import 'profile_models.dart';

/// Reads and updates the caller's own profile, locations, and verification.
class ProfileRepository {
  /// Creates the repository over an [ApiClient].
  ProfileRepository({required ApiClient apiClient}) : _api = apiClient;

  final ApiClient _api;

  /// Reads the caller's own profile snapshot (incl. the live tier).
  ///
  /// Maps to `GET /profiles/me`. Authenticated.
  Future<Me> getMe() async {
    final result = await _api.get<Me>(
      '/profiles/me',
      parser: (data) => Me.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Reads only the caller's live tier (cheaper than full `me`).
  ///
  /// Maps to `GET /profiles/me/tier` → `{ tier: "Tn" }`.
  Future<String> getTier() async {
    final result = await _api.get<String>(
      '/profiles/me/tier',
      parser: (data) => (data! as Map<String, dynamic>)['tier'] as String,
    );
    return result.data;
  }

  /// Completes the profile (PATCH); may promote T1→T2. Returns the new tier.
  ///
  /// Maps to `PATCH /profiles/me`. Only non-null fields are changed server-side.
  Future<String> updateProfile({
    String? firstName,
    String? lastName,
    String? dateOfBirth,
    String? gender,
    String? nationality,
  }) async {
    final result = await _api.post<String>(
      '/profiles/me',
      body: {
        'firstName': ?firstName,
        'lastName': ?lastName,
        'dateOfBirth': ?dateOfBirth,
        'gender': ?gender,
        'nationality': ?nationality,
      },
      parser: (data) => (data! as Map<String, dynamic>)['tier'] as String,
    );
    return result.data;
  }

  /// Adds a ward location to the profile (the ≥1-pin half of T2).
  ///
  /// Maps to `POST /profiles/me/locations`. Returns the recomputed live tier so
  /// a T2 promotion shows immediately. [associationType] is a backend
  /// `AssociationType` name (e.g. `RESIDENCE`).
  Future<String> addLocation({
    required String wardPublicId,
    required String associationType,
    bool primary = false,
  }) async {
    final result = await _api.post<String>(
      '/profiles/me/locations',
      body: {
        'wardPublicId': wardPublicId,
        'associationType': associationType,
        'primary': primary,
      },
      parser: (data) => (data! as Map<String, dynamic>)['tier'] as String,
    );
    return result.data;
  }

  /// Submits a government ID for verification (→ PENDING, operator-assisted).
  ///
  /// Maps to `POST /profiles/me/verification`. [idType] is a backend `IdType`
  /// name (`NATIONAL`/`VOTER`/`PASSPORT`). The `idNo` is the most sensitive PII
  /// on the platform — it is sent once and never echoed back. Returns the request
  /// status (e.g. `PENDING`). Voter-ID success sets the electoral location
  /// authoritatively server-side (D13).
  Future<String> submitVerification({
    required String idType,
    required String idNo,
    required String fullName,
    String? evidenceRef,
  }) async {
    final result = await _api.post<String>(
      '/profiles/me/verification',
      body: {
        'idType': idType,
        'idNo': idNo,
        'fullName': fullName,
        'evidenceRef': ?evidenceRef,
      },
      parser: (data) => (data! as Map<String, dynamic>)['status'] as String,
    );
    return result.data;
  }
}
