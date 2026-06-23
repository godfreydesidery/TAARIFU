/// The composition root: builds the singleton infrastructure (config, token
/// store, connectivity, API client, cache, offline outbox) and the repositories
/// once, for injection into BLoCs.
///
/// WHY a hand-rolled container (no get_it for the foundation): the graph is
/// small and the wiring is explicit and analysable. A DI package can be
/// introduced later without changing call sites that already depend on the
/// repository interfaces (clean boundaries, CLAUDE.md §3).
library;

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
import '../storage/json_cache.dart';
import '../storage/outbox_store.dart';
import '../storage/token_store.dart';

/// Owns the app's long-lived singletons and repositories.
class AppDependencies {
  /// Wires the whole graph from [config].
  AppDependencies({required AppConfig config})
    : _tokenStore = TokenStore(),
      _connectivity = ConnectivityService(),
      _cache = InMemoryJsonCache(),
      _outbox = InMemoryOutboxStore() {
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
