/// Repository for PDPA data-subject-request self-service (PRD §18 PDPA, §25.1,
/// UC-A16/UC-A17; `/privacy/dsr/**`).
///
/// Responsibility: turn the citizen's two data rights — ACCESS (export my data)
/// and ERASURE (delete my data) — into [ApiClient] calls against the real
/// `DataSubjectRequestController`. The subject is always bound server-side from
/// the bearer token (never a path/body id), so every call here is strictly the
/// caller acting on their own data; the client never sends an account id.
///
/// Both opens are idempotent server-side (re-opening an already-open ACCESS or
/// ERASURE returns the existing tracked request), so the screen can let a citizen
/// tap again without creating duplicate requests.
library;

import '../../../core/network/api_client.dart';
import 'dsr_models.dart';

/// Opens and tracks the caller's own data-subject requests.
class DsrRepository {
  /// Creates the repository over an [ApiClient].
  DsrRepository({required ApiClient apiClient}) : _api = apiClient;

  final ApiClient _api;

  /// Lists the caller's own open requests for status tracking.
  ///
  /// Maps to `GET /privacy/dsr/mine`. Authenticated; the subject is the caller.
  Future<List<DataSubjectRequest>> listMine() async {
    final result = await _api.get<List<Map<String, dynamic>>>(
      '/privacy/dsr/mine',
      parser: _asMapList,
    );
    return result.data
        .map(DataSubjectRequest.fromJson)
        .toList(growable: false);
  }

  /// Opens an ACCESS (export) request (idempotent on an open ACCESS request).
  ///
  /// Maps to `POST /privacy/dsr/access`. Returns the tracked request so the UI
  /// can show its SLA + status. The actual export is fetched separately once the
  /// operator fulfils it (the self-service `GET /privacy/dsr/access/export`).
  Future<DataSubjectRequest> requestAccess() async {
    final result = await _api.post<DataSubjectRequest>(
      '/privacy/dsr/access',
      parser: (data) =>
          DataSubjectRequest.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Opens an ERASURE ("right to be forgotten") request (idempotent).
  ///
  /// Maps to `POST /privacy/dsr/erasure`. The backend blocks this for an account
  /// holding active staff/representative roles and surfaces that as a domain
  /// error the UI shows — the client does not pre-judge eligibility.
  Future<DataSubjectRequest> requestErasure() async {
    final result = await _api.post<DataSubjectRequest>(
      '/privacy/dsr/erasure',
      parser: (data) =>
          DataSubjectRequest.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
