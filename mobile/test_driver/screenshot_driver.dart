// Screenshot driver for the Phase-2 capture harness
// (integration_test/phase2_screenshots_test.dart).
//
// Writes each `binding.takeScreenshot(name)` PNG to
// `e2e-screenshots/phase2-mobile/<name>.png` at the repo root. Dev tooling only.
import 'dart:io';

import 'package:integration_test/integration_test_driver_extended.dart';

Future<void> main() async {
  await integrationDriver(
    onScreenshot: (String name, List<int> bytes, [Map<String, Object?>? args]) async {
      final dir = Directory('../e2e-screenshots/phase2-mobile');
      if (!dir.existsSync()) {
        dir.createSync(recursive: true);
      }
      final file = File('${dir.path}/$name.png');
      file.writeAsBytesSync(bytes);
      // ignore: avoid_print
      print('SCREENSHOT WROTE ${file.absolute.path}');
      return true;
    },
  );
}
