/// The "Report an issue" screen (Ripoti changamoto) — US-3.1, UC-D01/D03.
///
/// A ≤2-minute flow: category picker → title/description (with a voice-to-text
/// seam) → photo attach seam → location (GPS/manual ward) → visibility → submit.
/// The submit is **offline-aware**: with no network the draft is queued and the
/// citizen is told it will sync later (the outbox lives in the repository).
///
/// The incident ward is chosen through the **manual ward picker** (replacing the
/// old hand-typed ward UUID) — see [WardPicker]; the GPS resolve seam remains
/// flagged for when `geolocator` lands.
///
/// Seams flagged as CENTRAL INTEGRATION NEEDS (kept out of scope to stay
/// dependency-light): the GPS button needs a `geolocator` + the GPS→ward resolve
/// call; the voice mic needs a `speech_to_text` engine (EI-17). Photo attach
/// (EI-8) is now a **live** capture seam — the camera/gallery affordances are
/// real and call an injectable [AttachmentService]; the default binding reports
/// "coming soon" rather than faking media, and captured items queue their refs
/// (local until the pre-signed upload endpoint lands). See attachment_service.dart.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../features/geography/data/geography_models.dart';
import '../../../features/geography/data/geography_repository.dart';
import '../../../features/geography/view/ward_picker.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/report_form_cubit.dart';
import '../data/attachment_models.dart';
import '../data/attachment_service.dart';
import '../data/reporting_models.dart';

/// The file-a-report form.
class ReportFormScreen extends StatefulWidget {
  /// Creates the screen over the shared [geographyRepository] (for the ward
  /// picker).
  const ReportFormScreen({required this.geographyRepository, super.key});

  /// Civic-geography reads backing the manual ward picker.
  final GeographyRepository geographyRepository;

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

  /// Opens the manual ward picker and stores the chosen ward.
  Future<void> _pickWard() async {
    final chosen = await WardPicker.open(
      context,
      geographyRepository: widget.geographyRepository,
    );
    if (chosen != null && mounted) {
      setState(() => _ward = chosen);
    }
  }

  /// Whether the chosen category forces PRIVATE (sensitive) — disables the
  /// visibility toggle and shows the safety note.
  bool get _forcedPrivate => _category?.forcePrivate ?? false;

  void _submit(AppLocalizations l10n) {
    // The ward is now picker-chosen, not a free-text field, so validate it
    // explicitly (a report must be geo-scoped to route to the right authority).
    if (!(_formKey.currentState?.validate() ?? false) ||
        _category == null ||
        _ward == null) {
      if (_ward == null) {
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(SnackBar(content: Text(l10n.requiredField)));
      }
      return;
    }
    final draft = ReportDraft(
      categoryId: _category!.id,
      title: _titleController.text.trim(),
      description: _descController.text.trim(),
      wardId: _ward!.id,
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
        listenWhen: (p, c) =>
            p.status != c.status || p.attachmentError != c.attachmentError,
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
          // A capture failure (cancelled / picker not wired) is non-fatal — the
          // report is still filable without media.
          if (state.attachmentError != null) {
            final msg = state.attachmentError is AttachmentUnavailable
                ? l10n.reportAttachUnavailable
                : FailureMessages.of(l10n, state.attachmentError!);
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(SnackBar(content: Text(msg)));
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
          // Photo-attach (EI-8): live camera/gallery capture seam. Captured items
          // queue their refs in the draft (local until the upload endpoint lands).
          _AttachmentsSection(
            attachments: state.attachments,
            busy: state.attachmentBusy,
            submitting: submitting,
          ),
          const SizedBox(height: 16),
          // Ward chosen through the manual ward picker (no hand-typed UUID).
          Card(
            margin: EdgeInsets.zero,
            child: ListTile(
              leading: const Icon(Icons.location_city_outlined),
              title: Text(
                _ward == null
                    ? l10n.reportWardLabel
                    : l10n.wardPickerChosenLabel(_ward!.qualifiedLabel),
              ),
              trailing: TextButton(
                onPressed: submitting ? null : _pickWard,
                child: Text(
                  _ward == null
                      ? l10n.wardPickerChooseButton
                      : l10n.wardPickerChangeButton,
                ),
              ),
              onTap: submitting ? null : _pickWard,
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

/// The attachment capture + preview block: camera/gallery buttons and a
/// thumbnail strip of held items, each removable. Reads the cubit directly so it
/// stays a small, const-friendly widget.
class _AttachmentsSection extends StatelessWidget {
  const _AttachmentsSection({
    required this.attachments,
    required this.busy,
    required this.submitting,
  });

  final List<PendingAttachment> attachments;
  final bool busy;
  final bool submitting;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final cubit = context.read<ReportFormCubit>();
    final locked = submitting || busy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(l10n.reportPhotoLabel, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: locked
                    ? null
                    : () => cubit.addAttachment(AttachmentSource.camera),
                icon: const Icon(Icons.photo_camera_outlined),
                label: Text(l10n.reportTakePhotoButton),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: locked
                    ? null
                    : () => cubit.addAttachment(AttachmentSource.gallery),
                icon: const Icon(Icons.photo_library_outlined),
                label: Text(l10n.reportPickPhotoButton),
              ),
            ),
          ],
        ),
        if (busy)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: LinearProgressIndicator(),
          ),
        if (attachments.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final a in attachments)
                Chip(
                  avatar: Icon(
                    a.isUploaded
                        ? Icons.cloud_done_outlined
                        : Icons.image_outlined,
                    size: 18,
                  ),
                  label: Text(
                    a.filename,
                    overflow: TextOverflow.ellipsis,
                  ),
                  onDeleted: submitting
                      ? null
                      : () => cubit.removeAttachment(a.localPath),
                ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            l10n.reportAttachQueuedNote,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ],
    );
  }
}
