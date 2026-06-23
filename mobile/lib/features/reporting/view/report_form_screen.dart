/// The "Report an issue" screen (Ripoti changamoto) — US-3.1, UC-D01/D03.
///
/// A ≤2-minute flow: category picker → title/description (with a voice-to-text
/// seam) → photo attach seam → location (GPS/manual ward) → visibility → submit.
/// The submit is **offline-aware**: with no network the draft is queued and the
/// citizen is told it will sync later (the outbox lives in the repository).
///
/// Seams flagged as CENTRAL INTEGRATION NEEDS (kept out of scope to stay
/// dependency-light): the GPS button needs a `geolocator` + the GPS→ward resolve
/// call; the voice mic needs a `speech_to_text` engine (EI-17); photo attach
/// needs an image picker + the (EI-8) pre-signed upload that yields the
/// `attachmentRefs`. Each is wired behind a disabled affordance here so the
/// contract and UX are visible without pulling the packages in yet.
///
/// The ward is chosen with the shared [WardPickerField] (no hand-typed UUID): the
/// citizen searches/browses a ward and the form binds its [WardSummary.id].
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/di/app_dependencies.dart';
import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../features/geography/data/geography_models.dart';
import '../../../features/geography/view/ward_picker_field.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/report_form_cubit.dart';
import '../data/reporting_models.dart';

/// The file-a-report form.
class ReportFormScreen extends StatefulWidget {
  /// Creates the screen over the app [dependencies] (for the ward picker).
  const ReportFormScreen({required this.dependencies, super.key});

  /// The composition root, supplied to the ward picker.
  final AppDependencies dependencies;

  @override
  State<ReportFormScreen> createState() => _ReportFormScreenState();
}

class _ReportFormScreenState extends State<ReportFormScreen> {
  final _formKey = GlobalKey<FormState>();
  final _titleController = TextEditingController();
  final _descController = TextEditingController();

  IssueCategory? _category;
  WardSummary? _ward;
  String _visibility = 'PUBLIC';
  bool _anonymous = false;

  @override
  void dispose() {
    _titleController.dispose();
    _descController.dispose();
    super.dispose();
  }

  /// Whether the chosen category forces PRIVATE (sensitive) — disables the
  /// visibility toggle and shows the safety note.
  bool get _forcedPrivate => _category?.forcePrivate ?? false;

  void _submit(AppLocalizations l10n) {
    final ward = _ward;
    if (!(_formKey.currentState?.validate() ?? false) ||
        _category == null ||
        ward == null) {
      // A missing ward is shown inline (see the WardPickerField error text below);
      // bail so we never file with an empty ward id.
      setState(() {});
      return;
    }
    final draft = ReportDraft(
      categoryId: _category!.id,
      title: _titleController.text.trim(),
      description: _descController.text.trim(),
      wardId: ward.id,
      visibility: _forcedPrivate ? 'PRIVATE' : _visibility,
      anonymous: _anonymous && (_category?.sensitive ?? false),
    );
    context.read<ReportFormCubit>().submit(draft);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.reportTitle)),
      body: BlocConsumer<ReportFormCubit, ReportFormState>(
        listenWhen: (p, c) => p.status != c.status,
        listener: (context, state) {
          if (state.status == ReportFormStatus.filed ||
              state.status == ReportFormStatus.queued) {
            _showResultSheet(context, l10n, state);
          } else if (state.status == ReportFormStatus.submitFailed &&
              state.error != null) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(content: Text(FailureMessages.of(l10n, state.error!))),
              );
          }
        },
        builder: (context, state) {
          switch (state.status) {
            case ReportFormStatus.loadingCategories:
              return LoadingView(label: l10n.loadingLabel);
            case ReportFormStatus.categoriesFailed:
              return ErrorRetryView(
                message: FailureMessages.of(l10n, state.error!),
                retryLabel: l10n.retryButton,
                onRetry: () => context.read<ReportFormCubit>().loadCategories(),
              );
            default:
              return _form(context, l10n, state);
          }
        },
      ),
    );
  }

  Widget _form(
    BuildContext context,
    AppLocalizations l10n,
    ReportFormState state,
  ) {
    final submitting = state.status == ReportFormStatus.submitting;
    return Form(
      key: _formKey,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          DropdownButtonFormField<IssueCategory>(
            initialValue: _category,
            isExpanded: true,
            decoration: InputDecoration(
              labelText: l10n.reportCategoryLabel,
              hintText: l10n.reportCategoryHint,
            ),
            items: [
              for (final c in state.categories)
                DropdownMenuItem(value: c, child: Text(c.name)),
            ],
            validator: (v) => v == null ? l10n.requiredField : null,
            onChanged: submitting
                ? null
                : (v) => setState(() {
                    _category = v;
                    if (v?.forcePrivate ?? false) _visibility = 'PRIVATE';
                  }),
          ),
          if (_category?.sensitive ?? false)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                l10n.reportSensitiveNote,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if ((_category?.defaultSlaTtfrMinutes ?? 0) > 0)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                l10n.reportSlaNote(
                  (_category!.defaultSlaTtfrMinutes / 60).ceil(),
                ),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _titleController,
            decoration: InputDecoration(labelText: l10n.reportTitleLabel),
            maxLength: 200,
            validator: (v) =>
                (v == null || v.trim().isEmpty) ? l10n.requiredField : null,
          ),
          const SizedBox(height: 8),
          TextFormField(
            controller: _descController,
            decoration: InputDecoration(
              labelText: l10n.reportDescriptionLabel,
              helperText: l10n.reportVoiceHint,
              // Voice-to-text seam (EI-17) — disabled until the engine is wired.
              suffixIcon: IconButton(
                tooltip: l10n.reportVoiceHint,
                icon: const Icon(Icons.mic_none),
                onPressed: null,
              ),
            ),
            maxLines: 4,
            maxLength: 4000,
            validator: (v) =>
                (v == null || v.trim().isEmpty) ? l10n.requiredField : null,
          ),
          const SizedBox(height: 8),
          // Photo-attach seam (EI-8) — disabled until image-picker + pre-signed
          // upload yield the attachmentRefs.
          OutlinedButton.icon(
            onPressed: null,
            icon: const Icon(Icons.add_a_photo_outlined),
            label: Text(l10n.reportAddPhotoButton),
          ),
          const SizedBox(height: 16),
          // Ward picker (replaces the old hand-typed UUID): search/browse a Kata,
          // bind its id. The minimum pin granularity that routes the report.
          WardPickerField(
            dependencies: widget.dependencies,
            selected: _ward,
            enabled: !submitting,
            labelText: l10n.reportWardLabel,
            onSelected: (w) => setState(() => _ward = w),
          ),
          if (_ward == null)
            Padding(
              padding: const EdgeInsets.only(top: 6, left: 12),
              child: Text(
                l10n.wardFieldRequired,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                  fontSize: 12,
                ),
              ),
            ),
          const SizedBox(height: 8),
          // GPS resolve seam (EI-7) — disabled until geolocator + resolve wired.
          OutlinedButton.icon(
            onPressed: null,
            icon: const Icon(Icons.my_location),
            label: Text(l10n.reportUseGpsButton),
          ),
          const SizedBox(height: 16),
          Text(
            l10n.reportVisibilityLabel,
            style: Theme.of(context).textTheme.titleSmall,
          ),
          RadioGroup<String>(
            groupValue: _visibility,
            // RadioGroup.onChanged is non-nullable; when the choice is locked
            // (submitting, or a sensitive category forced PRIVATE) we ignore it.
            onChanged: (v) {
              if (submitting || _forcedPrivate || v == null) return;
              setState(() => _visibility = v);
            },
            child: Column(
              children: [
                RadioListTile<String>(
                  value: 'PUBLIC',
                  title: Text(l10n.reportVisibilityPublic),
                ),
                RadioListTile<String>(
                  value: 'PRIVATE',
                  title: Text(l10n.reportVisibilityPrivate),
                ),
              ],
            ),
          ),
          if (_category?.sensitive ?? false)
            SwitchListTile(
              value: _anonymous,
              onChanged: submitting
                  ? null
                  : (v) => setState(() => _anonymous = v),
              title: Text(l10n.reportAnonymousLabel),
            ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: submitting ? null : () => _submit(l10n),
            child: submitting
                ? const SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(l10n.reportSubmitButton),
          ),
        ],
      ),
    );
  }

  void _showResultSheet(
    BuildContext context,
    AppLocalizations l10n,
    ReportFormState state,
  ) {
    final queued = state.status == ReportFormStatus.queued;
    showModalBottomSheet<void>(
      context: context,
      isDismissible: false,
      builder: (sheetContext) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Icon(
              queued ? Icons.cloud_off : Icons.check_circle,
              size: 48,
              color: queued
                  ? Theme.of(context).colorScheme.outline
                  : Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(height: 12),
            Text(
              queued ? l10n.reportQueuedTitle : l10n.reportFiledTitle,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            Text(
              queued
                  ? l10n.reportQueuedBody
                  : l10n.reportFiledCode(state.filed?.code ?? ''),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: () {
                Navigator.of(sheetContext).pop();
                Navigator.of(context).pop(true);
              },
              child: Text(l10n.reportDoneButton),
            ),
          ],
        ),
      ),
    );
  }
}
