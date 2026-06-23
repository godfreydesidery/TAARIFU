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
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromEnvironment();
  final dependencies = AppDependencies(config: config);
  runApp(TaarifuApp(dependencies: dependencies));
}
