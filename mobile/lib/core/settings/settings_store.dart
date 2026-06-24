/// Durable persistence for [AppSettings] — a tiny disk-backed store so the
/// citizen's language/data-saver choice survives a cold start.
///
/// WHY a single JSON file (not `shared_preferences`/Hive): the dependency budget
/// is tight — every package grows the APK a citizen on a small bundle must
/// download (PRD §15, pubspec rationale). Settings are one small, non-sensitive
/// record, so a whole-file read/write is correct and simple (KISS), mirroring the
/// [FileOutboxStore] convention and reusing the same app-support directory. It is
/// behind an interface so a `shared_preferences` swap (or test fake) is a drop-in
/// that does not touch the settings cubit (clean boundaries, CLAUDE.md §3).
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'app_settings.dart';

/// Read/write contract for the citizen's preferences.
abstract interface class SettingsStore {
  /// Loads the persisted settings, or [AppSettings.defaults] on a miss.
  Future<AppSettings> load();

  /// Persists [settings].
  Future<void> save(AppSettings settings);
}

/// Resolves where the settings file lives (async so production can await
/// `path_provider`), keeping this class free of Flutter plugins for unit tests.
typedef SettingsFileResolver = Future<File> Function();

/// A disk-backed [SettingsStore] persisting to a single JSON file.
class FileSettingsStore implements SettingsStore {
  /// Creates the store over a [fileResolver] that yields the backing file.
  FileSettingsStore({required SettingsFileResolver fileResolver})
    : _resolveFile = fileResolver;

  final SettingsFileResolver _resolveFile;

  @override
  Future<AppSettings> load() async {
    try {
      final file = await _resolveFile();
      if (!await file.exists()) {
        return AppSettings.defaults;
      }
      final raw = await file.readAsString();
      if (raw.trim().isEmpty) {
        return AppSettings.defaults;
      }
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        return AppSettings.fromJson(decoded);
      }
      return AppSettings.defaults;
    } on Object {
      // Unreadable/corrupt settings must never brick startup — fall back.
      return AppSettings.defaults;
    }
  }

  @override
  Future<void> save(AppSettings settings) async {
    final file = await _resolveFile();
    await file.parent.create(recursive: true);
    final tmp = File('${file.path}.tmp');
    await tmp.writeAsString(jsonEncode(settings.toJson()), flush: true);
    // Atomic swap so a crash mid-write never leaves a half-written file.
    await tmp.rename(file.path);
  }
}

/// In-memory [SettingsStore] for tests (no disk, no plugins).
class InMemorySettingsStore implements SettingsStore {
  /// Creates the store, optionally seeded with [initial] settings.
  InMemorySettingsStore([AppSettings? initial])
    : _settings = initial ?? AppSettings.defaults;

  AppSettings _settings;

  @override
  Future<AppSettings> load() async => _settings;

  @override
  Future<void> save(AppSettings settings) async {
    _settings = settings;
  }
}
