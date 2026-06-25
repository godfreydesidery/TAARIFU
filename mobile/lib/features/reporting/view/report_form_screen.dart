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
import '../../../core/theme/app_palette.dart';
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
    final scheme = Theme.of(context).colorScheme;
    return Form(
      key: _formKey,
      child: ListView(
        padding: const EdgeInsets.all(AppPalette.spaceLg),
        children: [
          // --- Step 1: category --------------------------------------------
          _SectionCard(
            step: 1,
            icon: Icons.category_outlined,
            title: l10n.reportCategoryLabel,
            child: FormField<IssueCategory>(
              initialValue: _category,
              validator: (v) => v == null ? l10n.requiredField : null,
              builder: (field) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: AppPalette.spaceSm,
                    runSpacing: AppPalette.spaceSm,
                    children: [
                      for (final c in state.categories)
                        ChoiceChip(
                          label: Text(c.name),
                          selected: _category == c,
                          onSelected: submitting
                              ? null
                              : (_) {
                                  setState(() {
                                    _category = c;
                                    if (c.forcePrivate) _visibility = 'PRIVATE';
                                  });
                                  field.didChange(c);
                                },
                        ),
                    ],
                  ),
                  if (field.hasError)
                    Padding(
                      padding: const EdgeInsets.only(top: AppPalette.spaceSm),
                      child: Text(
                        field.errorText!,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: scheme.error,
                        ),
                      ),
                    ),
                  if (_category?.sensitive ?? false)
                    Padding(
                      padding: const EdgeInsets.only(top: AppPalette.spaceSm),
                      child: Text(
                        l10n.reportSensitiveNote,
                        style: TextStyle(color: scheme.error),
                      ),
                    ),
                  if ((_category?.defaultSlaTtfrMinutes ?? 0) > 0)
                    Padding(
                      padding: const EdgeInsets.only(top: AppPalette.spaceSm),
                      child: Row(
                        children: [
                          Icon(
                            Icons.schedule_outlined,
                            size: 16,
                            color: scheme.primary,
                          ),
                          const SizedBox(width: AppPalette.spaceXs),
                          Expanded(
                            child: Text(
                              l10n.reportSlaNote(
                                (_category!.defaultSlaTtfrMinutes / 60).ceil(),
                              ),
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: AppPalette.spaceMd),
          // --- Step 2: what happened ---------------------------------------
          _SectionCard(
            step: 2,
            icon: Icons.edit_note_outlined,
            title: l10n.reportTitleLabel,
            child: Column(
              children: [
                TextFormField(
                  controller: _titleController,
                  decoration: InputDecoration(labelText: l10n.reportTitleLabel),
                  maxLength: 200,
                  validator: (v) => (v == null || v.trim().isEmpty)
                      ? l10n.requiredField
                      : null,
                ),
                const SizedBox(height: AppPalette.spaceSm),
                TextFormField(
                  controller: _descController,
                  decoration: InputDecoration(
                    labelText: l10n.reportDescriptionLabel,
                    helperText: l10n.reportVoiceHint,
                    // Voice-to-text seam (EI-17) — disabled until wired.
                    suffixIcon: IconButton(
                      tooltip: l10n.reportVoiceHint,
                      icon: const Icon(Icons.mic_none),
                      onPressed: null,
                    ),
                  ),
                  maxLines: 4,
                  maxLength: 4000,
                  validator: (v) => (v == null || v.trim().isEmpty)
                      ? l10n.requiredField
                      : null,
                ),
              ],
            ),
          ),
          const SizedBox(height: AppPalette.spaceMd),
          // --- Step 3: photos (optional) -----------------------------------
          _SectionCard(
            step: 3,
            icon: Icons.add_a_photo_outlined,
            title: l10n.reportPhotoLabel,
            // Photo-attach (EI-8): live camera/gallery capture seam. Captured
            // items queue their refs in the draft (local until upload lands).
            child: _AttachmentsSection(
              attachments: state.attachments,
              busy: state.attachmentBusy,
              submitting: submitting,
            ),
          ),
          const SizedBox(height: AppPalette.spaceMd),
          // --- Step 4: location --------------------------------------------
          _SectionCard(
            step: 4,
            icon: Icons.place_outlined,
            title: l10n.reportWardLabel,
            child: Column(
              children: [
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.location_city_outlined),
                  title: Text(
                    _ward == null
                        ? l10n.wardPickerChooseButton
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
                // GPS resolve seam (EI-7) — disabled until geolocator wired.
                OutlinedButton.icon(
                  onPressed: null,
                  icon: const Icon(Icons.my_location),
                  label: Text(l10n.reportUseGpsButton),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppPalette.spaceMd),
          // --- Step 5: visibility ------------------------------------------
          _SectionCard(
            step: 5,
            icon: Icons.visibility_outlined,
            title: l10n.reportVisibilityLabel,
            child: Column(
              children: [
                RadioGroup<String>(
                  groupValue: _visibility,
                  // onChanged is non-nullable; when the choice is locked
                  // (submitting, or a sensitive category forced PRIVATE) ignore.
                  onChanged: (v) {
                    if (submitting || _forcedPrivate || v == null) return;
                    setState(() => _visibility = v);
                  },
                  child: Column(
                    children: [
                      RadioListTile<String>(
                        contentPadding: EdgeInsets.zero,
                        value: 'PUBLIC',
                        title: Text(l10n.reportVisibilityPublic),
                      ),
                      RadioListTile<String>(
                        contentPadding: EdgeInsets.zero,
                        value: 'PRIVATE',
                        title: Text(l10n.reportVisibilityPrivate),
                      ),
                    ],
                  ),
                ),
                if (_category?.sensitive ?? false)
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _anonymous,
                    onChanged: submitting
                        ? null
                        : (v) => setState(() => _anonymous = v),
                    title: Text(l10n.reportAnonymousLabel),
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppPalette.spaceXl),
          FilledButton.icon(
            onPressed: submitting ? null : () => _submit(l10n),
            icon: submitting
                ? const SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.send_rounded),
            label: Text(l10n.reportSubmitButton),
          ),
          const SizedBox(height: AppPalette.spaceXl),
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

/// An elegant, numbered section card wrapping one step of the report flow. The
/// number badge gives the form a clear, low-literacy-friendly "step N of the
/// form" rhythm (icons + numerals) without a heavy modal stepper that would be
/// fiddly on a small screen.
class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.step,
    required this.icon,
    required this.title,
    required this.child,
  });

  final int step;
  final IconData icon;
  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppPalette.spaceLg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 14,
                  backgroundColor: scheme.primary.withValues(alpha: 0.14),
                  foregroundColor: scheme.primary,
                  child: Text(
                    '$step',
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                      color: scheme.primary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                const SizedBox(width: AppPalette.spaceMd),
                Icon(icon, size: 20, color: scheme.onSurfaceVariant),
                const SizedBox(width: AppPalette.spaceSm),
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppPalette.spaceMd),
            child,
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
