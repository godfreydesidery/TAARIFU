/// The profile + verification screen (Wasifu wangu) — US-0.2, US-0.3, US-0.8.
///
/// Three sections gated by the live tier:
///   * verification status (tier indicator + phone/email/ID flags);
///   * complete profile (first/last name) → promotes T1→T2;
///   * verify ID/voter (idType + idNo + full name) → submits a PENDING request
///     that unlocks T3 on approval (operator-assisted at launch, D-Q2);
///   * manage locations (add a ward pin with an association type + primary flag).
///
/// The electoral location is deliberately NOT manually set here — it is voter-ID
/// authoritative (D13); the screen explains that instead of offering a control
/// that would let a citizen game binding-action scope.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/di/app_dependencies.dart';
import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../features/geography/data/geography_models.dart';
import '../../../features/geography/view/ward_picker_field.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/profile_cubit.dart';
import '../data/profile_models.dart';

/// The profile/verification view.
class ProfileScreen extends StatefulWidget {
  /// Creates the screen over the app [dependencies] (for the ward picker).
  const ProfileScreen({required this.dependencies, super.key});

  /// The composition root, supplied to the location ward picker.
  final AppDependencies dependencies;

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  final _firstController = TextEditingController();
  final _lastController = TextEditingController();
  final _idNoController = TextEditingController();
  final _fullNameController = TextEditingController();

  WardSummary? _ward;
  String _idType = 'NATIONAL';
  String _association = 'RESIDENCE';
  bool _primary = false;
  bool _prefilled = false;

  @override
  void dispose() {
    _firstController.dispose();
    _lastController.dispose();
    _idNoController.dispose();
    _fullNameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.profileTitle)),
      body: BlocConsumer<ProfileCubit, ProfileState>(
        listener: (context, state) {
          // Prefill name fields once the snapshot arrives.
          if (state.me != null && !_prefilled) {
            _firstController.text = state.me!.firstName ?? '';
            _lastController.text = state.me!.lastName ?? '';
            _prefilled = true;
          }
          final messenger = ScaffoldMessenger.of(context);
          if (state.error != null) {
            messenger
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(content: Text(FailureMessages.of(l10n, state.error!))),
              );
          } else if (state.action != ProfileAction.none) {
            messenger
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(content: Text(_actionMessage(l10n, state.action))),
              );
          }
        },
        builder: (context, state) {
          switch (state.status) {
            case ProfileStatus.initial:
            case ProfileStatus.loading:
              return LoadingView(label: l10n.loadingLabel);
            case ProfileStatus.failure:
              return ErrorRetryView(
                message: FailureMessages.of(l10n, state.error!),
                retryLabel: l10n.retryButton,
                onRetry: () => context.read<ProfileCubit>().load(),
              );
            case ProfileStatus.loaded:
              return _content(context, l10n, state);
          }
        },
      ),
    );
  }

  String _actionMessage(AppLocalizations l10n, ProfileAction a) =>
      switch (a) {
        ProfileAction.locationAdded => l10n.profileLocationAddedNote,
        ProfileAction.verificationSubmitted => l10n.profileVerifyPendingNote,
        _ => l10n.saveButton,
      };

  Widget _content(
    BuildContext context,
    AppLocalizations l10n,
    ProfileState state,
  ) {
    final me = state.me!;
    final busy = state.actionInFlight;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _tierCard(context, l10n, me),
        const SizedBox(height: 16),
        _profileSection(context, l10n, busy),
        const Divider(height: 32),
        _verifySection(context, l10n, me, busy),
        const Divider(height: 32),
        _locationsSection(context, l10n, busy),
      ],
    );
  }

  Widget _tierCard(BuildContext context, AppLocalizations l10n, Me me) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text('${l10n.profileTierLabel}: '),
                Chip(label: Text(me.tier)),
              ],
            ),
            const SizedBox(height: 8),
            _flagRow(l10n.profilePhoneVerified, me.phoneVerified),
            _flagRow(l10n.profileEmailVerified, me.emailVerified),
            _flagRow(l10n.profileIdVerified, me.idVerified),
          ],
        ),
      ),
    );
  }

  Widget _flagRow(String label, bool ok) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      children: [
        Icon(
          ok ? Icons.check_circle : Icons.radio_button_unchecked,
          size: 18,
          color: ok ? Colors.green : Colors.grey,
        ),
        const SizedBox(width: 8),
        Expanded(child: Text(label)),
      ],
    ),
  );

  Widget _profileSection(
    BuildContext context,
    AppLocalizations l10n,
    bool busy,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          l10n.profileCompleteHeader,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _firstController,
          decoration: InputDecoration(labelText: l10n.profileFirstNameLabel),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _lastController,
          decoration: InputDecoration(labelText: l10n.profileLastNameLabel),
        ),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  final first = _firstController.text.trim();
                  final last = _lastController.text.trim();
                  if (first.isEmpty || last.isEmpty) return;
                  context.read<ProfileCubit>().completeProfile(
                    firstName: first,
                    lastName: last,
                  );
                },
          child: Text(l10n.profileSaveButton),
        ),
      ],
    );
  }

  Widget _verifySection(
    BuildContext context,
    AppLocalizations l10n,
    Me me,
    bool busy,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          l10n.profileVerifyHeader,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        DropdownButtonFormField<String>(
          initialValue: _idType,
          decoration: InputDecoration(labelText: l10n.profileIdTypeLabel),
          items: [
            DropdownMenuItem(
              value: 'NATIONAL',
              child: Text(l10n.profileIdTypeNational),
            ),
            DropdownMenuItem(
              value: 'VOTER',
              child: Text(l10n.profileIdTypeVoter),
            ),
            DropdownMenuItem(
              value: 'PASSPORT',
              child: Text(l10n.profileIdTypePassport),
            ),
          ],
          onChanged: busy ? null : (v) => setState(() => _idType = v ?? 'NATIONAL'),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _idNoController,
          decoration: InputDecoration(labelText: l10n.profileIdNoLabel),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _fullNameController,
          decoration: InputDecoration(labelText: l10n.profileFullNameLabel),
        ),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  final idNo = _idNoController.text.trim();
                  final fullName = _fullNameController.text.trim();
                  if (idNo.isEmpty || fullName.isEmpty) return;
                  context.read<ProfileCubit>().submitVerification(
                    idType: _idType,
                    idNo: idNo,
                    fullName: fullName,
                  );
                },
          child: Text(l10n.profileSubmitVerifyButton),
        ),
      ],
    );
  }

  Widget _locationsSection(
    BuildContext context,
    AppLocalizations l10n,
    bool busy,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          l10n.profileLocationsHeader,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 4),
        Text(
          l10n.profileElectoralNote,
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 8),
        // Ward picker (replaces the old hand-typed UUID) for pinning a location.
        WardPickerField(
          dependencies: widget.dependencies,
          selected: _ward,
          enabled: !busy,
          onSelected: (w) => setState(() => _ward = w),
        ),
        const SizedBox(height: 8),
        DropdownButtonFormField<String>(
          initialValue: _association,
          decoration: InputDecoration(labelText: l10n.profileAssociationLabel),
          items: const [
            DropdownMenuItem(value: 'RESIDENCE', child: Text('RESIDENCE')),
            DropdownMenuItem(
              value: 'HOME_ANCESTRAL',
              child: Text('HOME_ANCESTRAL'),
            ),
            DropdownMenuItem(value: 'WORK', child: Text('WORK')),
            DropdownMenuItem(value: 'FAMILY', child: Text('FAMILY')),
            DropdownMenuItem(value: 'BUSINESS', child: Text('BUSINESS')),
            DropdownMenuItem(value: 'PROPERTY', child: Text('PROPERTY')),
            DropdownMenuItem(value: 'INTEREST', child: Text('INTEREST')),
          ],
          onChanged: busy
              ? null
              : (v) => setState(() => _association = v ?? 'RESIDENCE'),
        ),
        SwitchListTile(
          value: _primary,
          onChanged: busy ? null : (v) => setState(() => _primary = v),
          title: Text(l10n.profileSetPrimaryLabel),
          contentPadding: EdgeInsets.zero,
        ),
        FilledButton.icon(
          onPressed: busy
              ? null
              : () {
                  final ward = _ward;
                  if (ward == null) return;
                  context.read<ProfileCubit>().addLocation(
                    wardPublicId: ward.id,
                    associationType: _association,
                    primary: _primary,
                  );
                },
          icon: const Icon(Icons.add_location_alt_outlined),
          label: Text(l10n.profileAddLocationButton),
        ),
      ],
    );
  }
}
