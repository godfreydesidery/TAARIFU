/// Application entry point.
///
/// Builds [AppConfig] from `--dart-define` (so the base URL is never hardcoded),
/// wires the composition root, and runs [TaarifuApp].
library;

import 'package:flutter/material.dart';

import 'app.dart';
import 'core/config/app_config.dart';
import 'core/di/app_dependencies.dart';

/// Boots the Taarifu citizen app.
///
/// Async because the composition root resolves the **durable** offline outbox's
/// storage path before the first frame, so a draft queued offline survives a cold
/// start (PRD §15, UC-D03).
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromEnvironment();
  final dependencies = await AppDependencies.create(config: config);
  runApp(TaarifuApp(dependencies: dependencies));
}
