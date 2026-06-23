/// Repository for engagement: petitions, surveys/polls, and public Q&A
/// (PRD §10 Epics M8/M9/M10, UC-E01..E11).
///
/// Responsibility: turn the engagement use-cases into [ApiClient] calls against
/// the real `PetitionController`, `SurveyController`, and `QuestionController`.
/// The list reads are public (`permitAll`); the binding writes are tier-gated
/// server-side (sign = T3, respond = T3, ask = T2). The client surfaces the gate
/// up front (a UX hint) but the server is authoritative.
///
/// Read-through caching is applied to the lists so a citizen can browse offline
/// (these are public reference content; PRD §15, §22.6).
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/json_cache.dart';
import 'engagement_models.dart';

/// Reads engagement lists and performs the sign/respond/ask writes.
class EngagementRepository {
  /// Creates the repository over an [ApiClient] and a [JsonCache].
  EngagementRepository({required ApiClient apiClient, required JsonCache cache})
    : _api = apiClient,
      _cache = cache;

  final ApiClient _api;
  final JsonCache _cache;

  // --- Petitions -----------------------------------------------------------

  /// Lists public petitions (`GET /petitions`), cached for offline browse.
  Future<List<Petition>> listPetitions() => _cachedList(
    path: '/petitions',
    cacheKey: 'engagement.petitions.page0',
    fromJson: Petition.fromJson,
  );

  /// Signs a petition — binding, T3, one-per-person (`POST /petitions/{id}/signatures`).
  ///
  /// The signer identity comes from the bearer token, never the body. Returns
  /// the updated petition (incremented count).
  Future<Petition> signPetition(
    String petitionId, {
    String? comment,
    bool publicSignature = false,
  }) async {
    final result = await _api.post<Petition>(
      '/petitions/$petitionId/signatures',
      body: {
        'comment': ?comment,
        'publicSignature': publicSignature,
      },
      parser: (data) => Petition.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  // --- Surveys/Polls -------------------------------------------------------

  /// Lists public surveys/polls (`GET /surveys`), cached for offline browse.
  Future<List<Survey>> listSurveys() => _cachedList(
    path: '/surveys',
    cacheKey: 'engagement.surveys.page0',
    fromJson: Survey.fromJson,
  );

  /// Responds to a survey/poll (`POST /surveys/{id}/responses`).
  ///
  /// [answers] is the JSON answers payload aligned to the survey's questions; the
  /// respondent identity comes from the token (one-per-person). T3-gated.
  Future<Survey> respondToSurvey(String surveyId, {required String answers}) async {
    final result = await _api.post<Survey>(
      '/surveys/$surveyId/responses',
      body: {'answers': answers},
      parser: (data) => Survey.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  // --- Q&A -----------------------------------------------------------------

  /// Lists public Q&A questions (`GET /questions`), optionally for one rep.
  Future<List<Question>> listQuestions({String? targetRepId}) => _cachedList(
    path: '/questions',
    cacheKey: 'engagement.questions.${targetRepId ?? 'all'}',
    query: {'targetRepId': ?targetRepId},
    fromJson: Question.fromJson,
  );

  /// Asks a representative a public question (`POST /questions`). T2-gated;
  /// no-self-question enforced server-side.
  Future<Question> askQuestion({
    required String targetRepId,
    required String body,
  }) async {
    final result = await _api.post<Question>(
      '/questions',
      body: {'targetRepId': targetRepId, 'body': body},
      parser: (data) => Question.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  // --- Shared cached-list helper ------------------------------------------

  Future<List<T>> _cachedList<T>({
    required String path,
    required String cacheKey,
    required T Function(Map<String, dynamic>) fromJson,
    Map<String, dynamic>? query,
  }) async {
    final q = <String, dynamic>{
      'page': 0,
      'size': 20,
      ...?query,
    };
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        path,
        query: q,
        parser: _asMapList,
      );
      await _cache.write(cacheKey, result.data);
      return result.data.map(fromJson).toList(growable: false);
    } on Object {
      final cached = await _cache.read(cacheKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(fromJson)
            .toList(growable: false);
      }
      rethrow;
    }
  }

  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
