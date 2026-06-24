/// The Settings screen (Mipangilio) — language (SW/EN), data-saver, sign out,
/// and the app version (PRD §14 Swahili-first, §15 data-budget).
///
/// WHY these controls: language is the headline preference for a Swahili-first
/// audience that may prefer English; data-saver is a *visible*, first-class
/// switch because a citizen on a tiny bundle pays per megabyte (so they can
/// throttle heavy media/sync deliberately); sign out wipes the session securely;
/// and the version aids support over a patchy line. Strings are externalised
/// (CLAUDE.md §8); the language change takes effect immediately app-wide via the
/// root [SettingsCubit].
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/settings/app_settings.dart';
import '../../../core/settings/settings_cubit.dart';
import '../../../l10n/app_localizations.dart';

/// The settings screen.
class SettingsScreen extends StatelessWidget {
  /// Creates the screen. [appVersion] is the resolved package version string
  /// (e.g. `0.1.0+1`); [onSignOut] performs the secure logout.
  const SettingsScreen({
    required this.appVersion,
    required this.onSignOut,
    super.key,
  });

  /// The display version (`version+build`) shown at the bottom.
  final String appVersion;

  /// Called when the citizen confirms sign out.
  final VoidCallback onSignOut;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.settingsTitle)),
      body: BlocBuilder<SettingsCubit, AppSettings>(
        builder: (context, settings) {
          final cubit = context.read<SettingsCubit>();
          return ListView(
            children: [
              // --- Language -------------------------------------------------
              _SectionHeader(title: l10n.settingsLanguageHeader),
              RadioGroup<String>(
                groupValue: settings.languageCode,
                onChanged: (v) {
                  if (v != null) cubit.setLanguage(v);
                },
                child: const Column(
                  children: [
                    RadioListTile<String>(
                      value: 'sw',
                      title: Text('Kiswahili'),
                      secondary: Icon(Icons.translate),
                    ),
                    RadioListTile<String>(
                      value: 'en',
                      title: Text('English'),
                      secondary: Icon(Icons.translate),
                    ),
                  ],
                ),
              ),
              const Divider(),
              // --- Data saver -----------------------------------------------
              _SectionHeader(title: l10n.settingsDataHeader),
              SwitchListTile(
                value: settings.dataSaver,
                onChanged: cubit.setDataSaver,
                secondary: const Icon(Icons.data_saver_on),
                title: Text(l10n.settingsDataSaverLabel),
                subtitle: Text(l10n.settingsDataSaverSubtitle),
              ),
              const Divider(),
              // --- Account --------------------------------------------------
              ListTile(
                leading: Icon(
                  Icons.logout,
                  color: Theme.of(context).colorScheme.error,
                ),
                title: Text(l10n.logoutButton),
                onTap: () => _confirmSignOut(context, l10n),
              ),
              const Divider(),
              // --- About ----------------------------------------------------
              ListTile(
                leading: const Icon(Icons.info_outline),
                title: Text(l10n.settingsVersionLabel),
                subtitle: Text(appVersion),
              ),
            ],
          );
        },
      ),
    );
  }

  /// Confirms before wiping the session (a destructive, secure-wipe action).
  Future<void> _confirmSignOut(
    BuildContext context,
    AppLocalizations l10n,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.logoutButton),
        content: Text(l10n.settingsSignOutConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.cancelButton),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.logoutButton),
          ),
        ],
      ),
    );
    if (confirmed == true) onSignOut();
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
    child: Text(
      title,
      style: Theme.of(context).textTheme.titleSmall?.copyWith(
        color: Theme.of(context).colorScheme.primary,
      ),
    ),
  );
}
