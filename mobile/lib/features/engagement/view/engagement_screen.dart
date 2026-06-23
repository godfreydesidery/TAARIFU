/// The engagement hub (Shiriki) — petitions, surveys/polls, and Q&A (M8/M9/M10).
///
/// A 3-tab screen. Binding actions surface the tier gate (T3 to sign/respond, T2
/// to ask) before the call so a citizen below the bar gets a clear, localised
/// "verify to do this" prompt rather than a 403 (the server stays authoritative).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/engagement_cubit.dart';
import '../data/engagement_models.dart';

/// The engagement tabs.
class EngagementScreen extends StatelessWidget {
  /// Creates the screen. [onNeedVerification] routes to profile/verification.
  const EngagementScreen({required this.onNeedVerification, super.key});

  /// Called when a tier gate is hit, to lead the citizen to verification.
  final VoidCallback onNeedVerification;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: Text(l10n.engageTitle),
          bottom: TabBar(
            tabs: [
              Tab(text: l10n.engagePetitionsTab),
              Tab(text: l10n.engageSurveysTab),
              Tab(text: l10n.engageQaTab),
            ],
          ),
        ),
        body: BlocConsumer<EngagementCubit, EngagementState>(
          listenWhen: (p, c) =>
              p.action != c.action || (c.error != null && p.error != c.error),
          listener: (context, state) {
            final messenger = ScaffoldMessenger.of(context);
            if (state.error != null) {
              messenger
                ..hideCurrentSnackBar()
                ..showSnackBar(
                  SnackBar(
                    content: Text(FailureMessages.of(l10n, state.error!)),
                  ),
                );
            } else if (state.action == EngagementAction.tierTooLow) {
              messenger
                ..hideCurrentSnackBar()
                ..showSnackBar(
                  SnackBar(
                    content: Text(l10n.tierGateT3),
                    action: SnackBarAction(
                      label: l10n.goToProfileButton,
                      onPressed: onNeedVerification,
                    ),
                  ),
                );
              context.read<EngagementCubit>().clearAction();
            } else if (state.action != EngagementAction.none) {
              messenger
                ..hideCurrentSnackBar()
                ..showSnackBar(
                  SnackBar(content: Text(_actionText(l10n, state.action))),
                );
              context.read<EngagementCubit>().clearAction();
            }
          },
          builder: (context, state) => TabBarView(
            children: [
              _PetitionsTab(state: state),
              _SurveysTab(state: state),
              _QaTab(state: state, onNeedVerification: onNeedVerification),
            ],
          ),
        ),
      ),
    );
  }

  String _actionText(AppLocalizations l10n, EngagementAction a) => switch (a) {
    EngagementAction.signed => l10n.petitionSignedLabel,
    EngagementAction.responded => l10n.surveyRespondedLabel,
    EngagementAction.asked => l10n.qaAskedLabel,
    _ => '',
  };
}

// --- Petitions ------------------------------------------------------------

class _PetitionsTab extends StatelessWidget {
  const _PetitionsTab({required this.state});

  final EngagementState state;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    switch (state.petitionsStatus) {
      case EngagementListStatus.initial:
      case EngagementListStatus.loading:
        return LoadingView(label: l10n.loadingLabel);
      case EngagementListStatus.failure:
        return ErrorRetryView(
          message: FailureMessages.of(l10n, state.error ?? l10n.errorUnknown),
          retryLabel: l10n.retryButton,
          onRetry: () => context.read<EngagementCubit>().loadPetitions(),
        );
      case EngagementListStatus.loaded:
        if (state.petitions.isEmpty) {
          return EmptyView(message: l10n.petitionsEmpty);
        }
        return RefreshIndicator(
          onRefresh: () => context.read<EngagementCubit>().loadPetitions(),
          child: ListView.builder(
            padding: const EdgeInsets.all(12),
            itemCount: state.petitions.length,
            itemBuilder: (context, i) => _PetitionCard(
              petition: state.petitions[i],
              busy: state.actionInFlight,
            ),
          ),
        );
    }
  }
}

class _PetitionCard extends StatelessWidget {
  const _PetitionCard({required this.petition, required this.busy});

  final Petition petition;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              petition.title,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (petition.body != null) ...[
              const SizedBox(height: 4),
              Text(
                petition.body!,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            const SizedBox(height: 8),
            Text(
              l10n.petitionSignaturesLabel(
                petition.signatureCount,
                petition.signatureGoal,
              ),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (petition.signatureGoal > 0)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: LinearProgressIndicator(
                  value: (petition.signatureCount / petition.signatureGoal)
                      .clamp(0, 1)
                      .toDouble(),
                ),
              ),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: busy
                    ? null
                    : () => context
                          .read<EngagementCubit>()
                          .signPetition(petition.id),
                child: Text(l10n.petitionSignButton),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// --- Surveys --------------------------------------------------------------

class _SurveysTab extends StatelessWidget {
  const _SurveysTab({required this.state});

  final EngagementState state;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    switch (state.surveysStatus) {
      case EngagementListStatus.initial:
      case EngagementListStatus.loading:
        return LoadingView(label: l10n.loadingLabel);
      case EngagementListStatus.failure:
        return ErrorRetryView(
          message: FailureMessages.of(l10n, state.error ?? l10n.errorUnknown),
          retryLabel: l10n.retryButton,
          onRetry: () => context.read<EngagementCubit>().loadSurveys(),
        );
      case EngagementListStatus.loaded:
        if (state.surveys.isEmpty) {
          return EmptyView(message: l10n.surveysEmpty);
        }
        return RefreshIndicator(
          onRefresh: () => context.read<EngagementCubit>().loadSurveys(),
          child: ListView.builder(
            padding: const EdgeInsets.all(12),
            itemCount: state.surveys.length,
            itemBuilder: (context, i) => _SurveyCard(
              survey: state.surveys[i],
              busy: state.actionInFlight,
            ),
          ),
        );
    }
  }
}

class _SurveyCard extends StatelessWidget {
  const _SurveyCard({required this.survey, required this.busy});

  final Survey survey;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    survey.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                if (survey.binding)
                  Chip(
                    label: Text(l10n.surveyBindingBadge),
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
            if (survey.description != null) ...[
              const SizedBox(height: 4),
              Text(
                survey.description!,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
            ],
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: busy
                    ? null
                    : () => _respond(context, l10n),
                child: Text(l10n.surveyRespondButton),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Opens a minimal answer sheet. The questions payload is a Phase-2 JSON
  /// scaffold on the backend, so the client collects a single free-text answer
  /// and sends it as the JSON answers blob — a thin, honest first cut.
  void _respond(BuildContext context, AppLocalizations l10n) {
    final controller = TextEditingController();
    final cubit = context.read<EngagementCubit>();
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => Padding(
        padding: EdgeInsets.only(
          left: 16,
          right: 16,
          top: 16,
          bottom: MediaQuery.of(sheetContext).viewInsets.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(survey.title, style: Theme.of(sheetContext).textTheme.titleMedium),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              decoration: InputDecoration(labelText: l10n.surveyAnswerLabel),
              maxLines: 3,
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () {
                final text = controller.text.trim();
                if (text.isEmpty) return;
                cubit.respondToSurvey(
                  survey.id,
                  answers: '{"answer":"${text.replaceAll('"', r'\"')}"}',
                );
                Navigator.of(sheetContext).pop();
              },
              child: Text(l10n.submitButton),
            ),
          ],
        ),
      ),
    );
  }
}

// --- Q&A ------------------------------------------------------------------

class _QaTab extends StatelessWidget {
  const _QaTab({required this.state, required this.onNeedVerification});

  final EngagementState state;
  final VoidCallback onNeedVerification;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      body: _body(context, l10n),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showAsk(context, l10n),
        icon: const Icon(Icons.help_outline),
        label: Text(l10n.qaAskButton),
      ),
    );
  }

  Widget _body(BuildContext context, AppLocalizations l10n) {
    switch (state.questionsStatus) {
      case EngagementListStatus.initial:
      case EngagementListStatus.loading:
        return LoadingView(label: l10n.loadingLabel);
      case EngagementListStatus.failure:
        return ErrorRetryView(
          message: FailureMessages.of(l10n, state.error ?? l10n.errorUnknown),
          retryLabel: l10n.retryButton,
          onRetry: () => context.read<EngagementCubit>().loadQuestions(),
        );
      case EngagementListStatus.loaded:
        if (state.questions.isEmpty) {
          return EmptyView(message: l10n.qaEmpty);
        }
        return RefreshIndicator(
          onRefresh: () => context.read<EngagementCubit>().loadQuestions(),
          child: ListView.builder(
            padding: const EdgeInsets.all(12),
            itemCount: state.questions.length,
            itemBuilder: (context, i) =>
                _QuestionCard(question: state.questions[i]),
          ),
        );
    }
  }

  void _showAsk(BuildContext context, AppLocalizations l10n) {
    final repController = TextEditingController();
    final bodyController = TextEditingController();
    final cubit = context.read<EngagementCubit>();
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => Padding(
        padding: EdgeInsets.only(
          left: 16,
          right: 16,
          top: 16,
          bottom: MediaQuery.of(sheetContext).viewInsets.bottom + 16,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l10n.qaAskTitle, style: Theme.of(sheetContext).textTheme.titleMedium),
            const SizedBox(height: 12),
            TextField(
              controller: repController,
              decoration: InputDecoration(labelText: l10n.qaRepIdLabel),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: bodyController,
              decoration: InputDecoration(labelText: l10n.qaQuestionLabel),
              maxLines: 3,
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () {
                final rep = repController.text.trim();
                final body = bodyController.text.trim();
                if (rep.isEmpty || body.isEmpty) return;
                cubit.askQuestion(targetRepId: rep, body: body);
                Navigator.of(sheetContext).pop();
              },
              child: Text(l10n.qaAskButton),
            ),
          ],
        ),
      ),
    );
  }
}

class _QuestionCard extends StatelessWidget {
  const _QuestionCard({required this.question});

  final Question question;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(question.body),
            if (question.isAnswered) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  Chip(
                    label: Text(l10n.qaAnsweredBadge),
                    visualDensity: VisualDensity.compact,
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                question.answerBody!,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ],
          ],
        ),
      ),
    );
  }
}
