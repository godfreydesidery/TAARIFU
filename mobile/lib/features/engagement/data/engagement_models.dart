/// Wire models for the engagement endpoints, mirroring the backend DTOs in
/// `com.taarifu.engagement.api.dto` (PetitionController, SurveyController,
/// QuestionController).
library;

/// A petition (backend `PetitionDto`).
///
/// [signatureCount]/[signatureGoal] drive the progress display; the count is
/// one-per-person and never token-weighted (integrity fence, PRD §23.5). Signing
/// is a binding civic act requiring T3.
class Petition {
  /// Creates a petition.
  const Petition({
    required this.id,
    required this.title,
    required this.status,
    this.body,
    this.signatureGoal = 0,
    this.signatureCount = 0,
    this.response,
  });

  /// Petition public id (UUID string).
  final String id;

  /// Headline.
  final String title;

  /// Lifecycle status (DRAFT/ACTIVE/SUCCEEDED/RESPONDED/CLOSED).
  final String status;

  /// The ask body, or `null` in a lean list view.
  final String? body;

  /// Success threshold.
  final int signatureGoal;

  /// Current valid signature count.
  final int signatureCount;

  /// The target's published response, or `null`.
  final String? response;

  /// Parses a petition node.
  factory Petition.fromJson(Map<String, dynamic> json) => Petition(
    id: json['id'] as String,
    title: json['title'] as String? ?? '',
    status: json['status'] as String? ?? '',
    body: json['body'] as String?,
    signatureGoal: (json['signatureGoal'] as num?)?.toInt() ?? 0,
    signatureCount: (json['signatureCount'] as num?)?.toInt() ?? 0,
    response: json['response'] as String?,
  );
}

/// A survey/poll (backend `SurveyDto`).
///
/// [binding] marks a binding poll whose response requires T3 (the strictest tier
/// the respond endpoint declares); a non-binding survey is still T3-gated at the
/// endpoint today (a documented backend refinement).
class Survey {
  /// Creates a survey.
  const Survey({
    required this.id,
    required this.title,
    required this.type,
    required this.status,
    this.description,
    this.binding = false,
    this.questions,
  });

  /// Survey public id (UUID string).
  final String id;

  /// Title.
  final String title;

  /// `SURVEY` or `POLL`.
  final String type;

  /// Lifecycle status.
  final String status;

  /// Description, or `null`.
  final String? description;

  /// Whether responding is a binding act (T3 + one-per-person).
  final bool binding;

  /// Raw JSON questions definition, or `null` (Phase-2 scaffold).
  final String? questions;

  /// Parses a survey node.
  factory Survey.fromJson(Map<String, dynamic> json) => Survey(
    id: json['id'] as String,
    title: json['title'] as String? ?? '',
    type: json['type'] as String? ?? 'SURVEY',
    status: json['status'] as String? ?? '',
    description: json['description'] as String?,
    binding: json['binding'] == true,
    questions: json['questions'] as String?,
  );
}

/// A public Q&A question (backend `QuestionDto`).
///
/// [answerBody]/[answeredByRepId] are populated when ANSWERED. Asking is a T2
/// action; the asker's identity comes from the token, never the body.
class Question {
  /// Creates a question.
  const Question({
    required this.id,
    required this.body,
    required this.status,
    this.upvotes = 0,
    this.answerBody,
  });

  /// Question public id (UUID string).
  final String id;

  /// The question text.
  final String body;

  /// Lifecycle status (OPEN/ANSWERED/DECLINED/MODERATED).
  final String status;

  /// Upvote count.
  final int upvotes;

  /// The answer text once ANSWERED, or `null`.
  final String? answerBody;

  /// Whether the question has been answered.
  bool get isAnswered => status == 'ANSWERED' && answerBody != null;

  /// Parses a question node.
  factory Question.fromJson(Map<String, dynamic> json) => Question(
    id: json['id'] as String,
    body: json['body'] as String? ?? '',
    status: json['status'] as String? ?? '',
    upvotes: (json['upvotes'] as num?)?.toInt() ?? 0,
    answerBody: json['answerBody'] as String?,
  );
}
