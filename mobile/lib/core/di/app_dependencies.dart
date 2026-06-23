/// The composition root: builds the singleton infrastructure (config, token
/// store, connectivity, API client, cache, offline outbox) and the repositories
/// once, for injection into BLoCs.
///
/// WHY a hand-rolled container (no get_it for the foundation): the graph is
/// small and the wiring is explicit and analysable. A DI package can be
/// introduced later without changing call sites that already depend on the
/// repository interfaces (clean boundaries, CLAUDE.md §3).
///
/// WHY [create] is async: the durable [OutboxStore] persists under the app
/// documents directory, resolved at runtime via `path_provider` (an async call).
/// The composition root therefore awaits that path once at boot, then wires the
/// rest of the graph synchronously around the resolved [OutboxStore].
library;

import 'package:path_provider/path_provider.dart';

import '../../features/auth/data/auth_repository.dart';
import '../../features/engagement/data/engagement_repository.dart';
import '../../features/feed/data/feed_repository.dart';
import '../../features/geography/data/geography_repository.dart';
import '../../features/notifications/data/notification_repository.dart';
import '../../features/profile/data/profile_repository.dart';
import '../../features/reporting/data/category_repository.dart';
import '../../features/reporting/data/report_repository.dart';
import '../../features/representatives/data/representative_repository.dart';
import '../config/app_config.dart';
import '../network/api_client.dart';
import '../network/connectivity_service.dart';
import '../storage/file_outbox_store.dart';
import '../storage/json_cache.dart';
import '../storage/outbox_store.dart';
import '../storage/token_store.dart';

/// Owns the app's long-lived singletons and repositories.
class AppDependencies {
  /// Wires the whole graph from [config] over an already-resolved [outbox].
  ///
  /// Private so callers go through [create], which resolves the durable outbox's
  /// storage location asynchronously before constructing the graph.
  AppDependencies._({required AppConfig config, required OutboxStore outbox})
    : _tokenStore = TokenStore(),
      _connectivity = ConnectivityService(),
      _cache = InMemoryJsonCache(),
      _outbox = outbox {
    _apiClient = ApiClient(
      config: config,
      tokenStore: _tokenStore,
      connectivity: _connectivity,
    );
    authRepository = AuthRepository(
      apiClient: _apiClient,
      tokenStore: _tokenStore,
    );
    geographyRepository = GeographyRepository(
      apiClient: _apiClient,
      cache: _cache,
    );
    representativeRepository = RepresentativeRepository(
      apiClient: _apiClient,
      cache: _cache,
    );
    feedRepository = FeedRepository(apiClient: _apiClient, cache: _cache);
    categoryRepository = CategoryRepository(
      apiClient: _apiClient,
      cache: _cache,
    );
    reportRepository = ReportRepository(
      apiClient: _apiClient,
      outbox: _outbox,
    );
    engagementRepository = EngagementRepository(
      apiClient: _apiClient,
      cache: _cache,
    );
    profileRepository = ProfileRepository(apiClient: _apiClient);
    notificationRepository = NotificationRepository(
      apiClient: _apiClient,
      cache: _cache,
    );
  }

  /// Builds the composition root, resolving the **durable** outbox's storage
  /// location first so offline drafts survive a cold start (PRD §15, UC-D03).
  ///
  /// The outbox file lives under the app documents directory (private, persistent,
  /// not user-visible), resolved via `path_provider`. If that resolution fails on a
  /// device (rare), we still boot with an in-memory outbox rather than blocking the
  /// citizen from using the app — drafts then live only for the session (fail-safe,
  /// CLAUDE.md §3). [outboxOverride] lets tests inject a store without a platform
  /// channel.
  static Future<AppDependencies> create({
    required AppConfig config,
    OutboxStore? outboxOverride,
  }) async {
    OutboxStore outbox = outboxOverride ?? InMemoryOutboxStore();
    if (outboxOverride == null) {
      try {
        final dir = await getApplicationDocumentsDirectory();
        outbox = FileOutboxStore.inDirectory(dir);
      } on Object {
        // Keep the in-memory fallback already assigned above.
      }
    }
    return AppDependencies._(config: config, outbox: outbox);
  }

  final TokenStore _tokenStore;
  final ConnectivityService _connectivity;
  final JsonCache _cache;
  final OutboxStore _outbox;
  late final ApiClient _apiClient;

  /// Auth/session repository.
  late final AuthRepository authRepository;

  /// Civic-geography reads.
  late final GeographyRepository geographyRepository;

  /// Find-my-representatives reads.
  late final RepresentativeRepository representativeRepository;

  /// Personalised-feed reads.
  late final FeedRepository feedRepository;

  /// Issue-category picker reads.
  late final CategoryRepository categoryRepository;

  /// Report filing/tracking + offline draft queue.
  late final ReportRepository reportRepository;

  /// Petitions/surveys/Q&A reads + binding writes.
  late final EngagementRepository engagementRepository;

  /// Profile/verification/locations reads + writes.
  late final ProfileRepository profileRepository;

  /// Notification inbox + preferences.
  late final NotificationRepository notificationRepository;
}
