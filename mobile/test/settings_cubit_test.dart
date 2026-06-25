/// Tests the settings cubit: language + data-saver + theme changes emit and
/// persist (so the choice survives a restart), and a no-op change does not
/// re-save.
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/settings/app_settings.dart';
import 'package:taarifu_citizen/core/settings/settings_cubit.dart';
import 'package:taarifu_citizen/core/settings/settings_store.dart';

void main() {
  group('SettingsCubit', () {
    test('defaults are Swahili, data-saver off, system theme (PRD §14)', () {
      const s = AppSettings.defaults;
      expect(s.languageCode, 'sw');
      expect(s.dataSaver, isFalse);
      expect(s.themeMode, ThemeMode.system);
    });

    blocTest<SettingsCubit, AppSettings>(
      'setThemeMode(dark) switches and persists',
      build: () => SettingsCubit(
        store: InMemorySettingsStore(),
        initial: AppSettings.defaults,
      ),
      act: (cubit) => cubit.setThemeMode(ThemeMode.dark),
      expect: () => [
        isA<AppSettings>().having((s) => s.themeMode, 'theme', ThemeMode.dark),
      ],
    );

    blocTest<SettingsCubit, AppSettings>(
      'a no-op theme change emits nothing',
      build: () => SettingsCubit(
        store: InMemorySettingsStore(),
        initial: AppSettings.defaults,
      ),
      act: (cubit) => cubit.setThemeMode(ThemeMode.system),
      expect: () => <AppSettings>[],
    );

    blocTest<SettingsCubit, AppSettings>(
      'setLanguage switches to English and persists',
      build: () => SettingsCubit(
        store: InMemorySettingsStore(),
        initial: AppSettings.defaults,
      ),
      act: (cubit) => cubit.setLanguage('en'),
      expect: () => [
        isA<AppSettings>().having((s) => s.languageCode, 'lang', 'en'),
      ],
    );

    blocTest<SettingsCubit, AppSettings>(
      'setDataSaver(true) toggles and persists',
      build: () => SettingsCubit(
        store: InMemorySettingsStore(),
        initial: AppSettings.defaults,
      ),
      act: (cubit) => cubit.setDataSaver(true),
      expect: () => [
        isA<AppSettings>().having((s) => s.dataSaver, 'dataSaver', true),
      ],
    );

    blocTest<SettingsCubit, AppSettings>(
      'a no-op change (same language) emits nothing',
      build: () => SettingsCubit(
        store: InMemorySettingsStore(),
        initial: AppSettings.defaults,
      ),
      act: (cubit) => cubit.setLanguage('sw'),
      expect: () => <AppSettings>[],
    );

    test('the change is written to the store (survives restart)', () async {
      final store = InMemorySettingsStore();
      final cubit = SettingsCubit(store: store, initial: AppSettings.defaults);
      await cubit.setLanguage('en');
      await cubit.setDataSaver(true);
      final reloaded = await store.load();
      expect(reloaded.languageCode, 'en');
      expect(reloaded.dataSaver, isTrue);
    });

    test('AppSettings round-trips through JSON, tolerating garbage', () {
      final json = const AppSettings(
        languageCode: 'en',
        dataSaver: true,
        themeMode: ThemeMode.dark,
      ).toJson();
      final back = AppSettings.fromJson(json);
      expect(back.languageCode, 'en');
      expect(back.dataSaver, isTrue);
      expect(back.themeMode, ThemeMode.dark);
      // An unknown language falls back to Swahili (the default).
      final fallback = AppSettings.fromJson({'languageCode': 'fr'});
      expect(fallback.languageCode, 'sw');
      // An absent/unknown themeMode falls back to system (a pre-theme settings
      // file written before dark mode existed must still load — backward compat).
      expect(fallback.themeMode, ThemeMode.system);
      expect(
        AppSettings.fromJson({'themeMode': 'nonsense'}).themeMode,
        ThemeMode.system,
      );
    });
  });
}
