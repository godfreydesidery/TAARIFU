/// Tests the social-feed presentation refresh:
///   * [FeedItem.fromJson] tolerates the legacy lean payload AND parses the new
///     optional social fields (kind/author/area/image/reaction) without breaking
///     the existing `/feed` contract.
///   * [FeedItemKind.fromCode] degrades unknown kinds to an announcement card.
///   * [FeedCard] renders an item's title, snippet, author, kind badge, and area
///     chip, and suppresses cover imagery under data-saver (PRD §15).
library;

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/features/feed/data/feed_models.dart';
import 'package:taarifu_citizen/features/feed/view/feed_card.dart';
import 'package:taarifu_citizen/l10n/app_localizations.dart';

/// Pumps [child] inside a minimal localized MaterialApp (Swahili-first).
Future<void> _pumpCard(WidgetTester tester, Widget child) async {
  await tester.pumpWidget(
    MaterialApp(
      locale: const Locale('sw'),
      supportedLocales: AppLocalizations.supportedLocales,
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: Scaffold(body: child),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('FeedItem.fromJson', () {
    test('parses the legacy lean payload (no social fields) gracefully', () {
      final item = FeedItem.fromJson({
        'id': 'a1',
        'title': 'Maji yamekatika',
        'snippet': 'Tatizo la maji Kata ya Mikocheni.',
      });
      expect(item.id, 'a1');
      expect(item.kind, FeedItemKind.announcement); // default
      expect(item.authorName, isNull);
      expect(item.areaName, isNull);
      expect(item.imageUrl, isNull);
      expect(item.reactionCount, 0);
    });

    test('parses the new optional social fields when present', () {
      final item = FeedItem.fromJson({
        'id': 'p1',
        'title': 'Ombi la barabara',
        'snippet': 'Saini ombi.',
        'kind': 'PETITION',
        'authorName': 'Halmashauri ya Kinondoni',
        'areaName': 'Kata ya Msasani',
        'imageUrl': 'https://example.org/x.webp',
        'reactionCount': 42,
      });
      expect(item.kind, FeedItemKind.petition);
      expect(item.authorName, 'Halmashauri ya Kinondoni');
      expect(item.areaName, 'Kata ya Msasani');
      expect(item.imageUrl, 'https://example.org/x.webp');
      expect(item.reactionCount, 42);
    });

    test('blank optional strings collapse to null (no empty chips)', () {
      final item = FeedItem.fromJson({
        'id': 'x',
        'title': 't',
        'snippet': 's',
        'authorName': '  ',
        'areaName': '',
        'imageUrl': '   ',
      });
      expect(item.authorName, isNull);
      expect(item.areaName, isNull);
      expect(item.imageUrl, isNull);
    });

    test('unknown kind degrades to an announcement (forward-compatible)', () {
      expect(FeedItemKind.fromCode('SOMETHING_NEW'), FeedItemKind.announcement);
      expect(FeedItemKind.fromCode(null), FeedItemKind.announcement);
      expect(FeedItemKind.fromCode('survey'), FeedItemKind.poll);
    });
  });

  group('FeedCard', () {
    testWidgets('renders title, snippet, author, and area', (tester) async {
      const item = FeedItem(
        id: 'a1',
        title: 'Maji yamekatika',
        snippet: 'Tatizo la maji.',
        kind: FeedItemKind.report,
        authorName: 'Diwani wa Mikocheni',
        areaName: 'Kata ya Mikocheni',
      );
      await _pumpCard(tester, FeedCard(item: item, onTap: () {}));

      expect(find.text('Maji yamekatika'), findsOneWidget);
      expect(find.text('Tatizo la maji.'), findsOneWidget);
      expect(find.text('Diwani wa Mikocheni'), findsOneWidget);
      expect(find.text('Kata ya Mikocheni'), findsOneWidget);
      // The report kind badge uses the localised Swahili label.
      final l10n = AppLocalizations.of(
        tester.element(find.byType(FeedCard)),
      );
      expect(find.text(l10n.feedKindReport), findsOneWidget);
    });

    testWidgets('tap invokes onTap (opens detail)', (tester) async {
      var tapped = false;
      const item = FeedItem(id: 'a1', title: 'T', snippet: 'S');
      await _pumpCard(
        tester,
        FeedCard(item: item, onTap: () => tapped = true),
      );
      await tester.tap(find.text('T'));
      expect(tapped, isTrue);
    });

    testWidgets('data-saver suppresses the cover image (PRD §15)', (
      tester,
    ) async {
      const item = FeedItem(
        id: 'a1',
        title: 'T',
        snippet: 'S',
        imageUrl: 'https://example.org/x.webp',
      );
      await _pumpCard(
        tester,
        FeedCard(item: item, onTap: () {}, dataSaver: true),
      );
      // No network image is attempted when data-saver is on.
      expect(find.byType(Image), findsNothing);
    });
  });
}
