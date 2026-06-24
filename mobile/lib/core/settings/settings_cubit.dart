/// App-wide settings cubit: holds the live [AppSettings] and persists every
/// change so language/data-saver take effect immediately and survive a restart.
///
/// WHY app-level (provided above [MaterialApp]): the language choice drives the
/// whole app's `locale`, so the cubit must sit at the root and rebuild the app on
/// change. Data-saver is read by data/UI layers (e.g. to skip heavy media) — it
/// is surfaced here as the single source of truth rather than a scattered flag.
library;

import 'package:flutter_bloc/flutter_bloc.dart';

import 'app_settings.dart';
import 'settings_store.dart';

/// Holds and mutates the citizen's [AppSettings].
class SettingsCubit extends Cubit<AppSettings> {
  /// Creates the cubit over a [SettingsStore], seeded with [initial] settings
  /// already loaded by the composition root (so the first frame is correct, with
  /// no flash of the default language).
  SettingsCubit({required SettingsStore store, required AppSettings initial})
    : _store = store,
      super(initial);

  final SettingsStore _store;

  /// Switches the UI language (`sw`/`en`) and persists it.
  Future<void> setLanguage(String languageCode) async {
    if (languageCode == state.languageCode) return;
    final next = state.copyWith(languageCode: languageCode);
    emit(next);
    await _store.save(next);
  }

  /// Toggles data-saver and persists it.
  Future<void> setDataSaver(bool enabled) async {
    if (enabled == state.dataSaver) return;
    final next = state.copyWith(dataSaver: enabled);
    emit(next);
    await _store.save(next);
  }
}
