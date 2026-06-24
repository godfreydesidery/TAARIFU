/// The composition root: builds the singleton infrastructure (config, token
/// store, connectivity, API client, cache, offline outbox, settings) and the
/// repositories once, for injection into BLoCs.
///
/// WHY a hand-rolled container (no get_it for the foundation): the graph is
/// small and the wiring is explicit and analysable. A DI package can be
/// introduced later without changing call sites that already depend on the
/// repository interfaces (clean boundaries, CLAUDE.md §3).
///
/// WHY an async [create] factory: the durable outbox and the settings store live
/// on disk (via `path_provider`), and the citizen's persisted settings must be
/// loaded *before the first frame* so the app opens in the right language with no
/// flash of the default locale. [create] awaits that load and seeds the
/// [settingsInitial]; the synchronous constructor then wires the rest.
library;

import 'dart:io';

import 'package:path_provider/path_provider.dart';

import '../../features/auth/data/auth_repository.dart';
import '../../features/engagement/data/engagement_repository.dart';
import '../../features/feed/data/feed_repository.dart';
import '../../features/geography/data/geography_repository.dart';
import '../../features/notifications/data/notification_repository.dart';
import '../../features/notifications/data/push_service.dart';
import '../../features/profile/data/profile_repository.dart';
import '../../features/reporting/data/attachment_service.dart';
import '../../features/reporting/data/category_repository.dart';
import '../../features/reporting/data/report_repository.dart';
import '../../features/representatives/data/representative_repository.dart';
import '../config/app_config.dart';
import '../network/api_client.dart';
import '../network/connectivity_service.dart';
import '../settings/app_settings.dart';
import '../settings/settings_store.dart';
import '../storage/file_outbox_store.dart';
import '../storage/json_cache.dart';
import '../storage/outbox_store.dart';
import '../storage/token_store.dart';

/// Owns the app's long-lived singletons and repositories.
class AppDependencies {
  /// Wires the whole graph from [config], with the citizen's persisted
  /// [settingsInitial] already loaded by [create].
  AppDependencies._({
    required AppConfig config,
    required SettingsStore store,
    required AppSettings initialSettings,
  }) : appConfig = config,
       settingsStore = store,
       settingsInitial = initialSettings,
       _tokenStore = TokenStore(),
       connectivity = ConnectivityService(),
       _cache = InMemoryJsonCache(),
       // Durable, disk-backed outbox: a report drafted offline survives the app
       // being killed before it could sync (PRD §15, UC-D03). The backing file
       // is resolved lazily (async path_provider) on first use. See
       // core/storage/file_outbox_store.dart.
       _outbox = FileOutboxStore(fileResolver: _resolveOutboxFile),
       // Push is a seam (no firebase_messaging in the budget yet); the default
       // surfaces SMS-fallback copy and no-ops token calls. See push_service.dart.
       pushService = const UnavailablePushService(),
       // Attachment capture is a seam (no image_picker in the budget yet); the
       // default reports "coming soon" rather than faking media. Swapping in an
       // ImagePickerAttachmentService here is the only change needed to ship
       // media end-to-end. See features/reporting/data/attachment_service.dart.
       attachmentService = const UnavailableAttachmentService() {
    _apiClient = ApiClient(
      config: config,
      tokenStore: _tokenStore,
      connectivity: connectivity,
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

  /// Builds the dependency graph, loading persisted settings first so the first
  /// frame opens in the citizen's chosen language (no locale flash).
  static Future<AppDependencies> create({required AppConfig config}) async {
    final settingsStore = FileSettingsStore(fileResolver: _resolveSettingsFile);
    final settings = await settingsStore.load();
    return AppDependencies._(
      config: config,
      store: settingsStore,
      initialSettings: settings,
    );
  }

  /// The resolved runtime configuration (base URL, app version).
  final AppConfig appConfig;

  /// The durable settings store (language, data-saver).
  final SettingsStore settingsStore;

  /// The settings loaded before the first frame (seeds the root SettingsCubit).
  final AppSettings settingsInitial;

  final TokenStore _tokenStore;

  /// Connectivity hints (reconnect → auto-flush the offline outbox).
  final ConnectivityService connectivity;

  final JsonCache _cache;
  final OutboxStore _outbox;
  late final ApiClient _apiClient;

  /// Push-notification seam (token lifecycle + tap-through deep links).
  final PushService pushService;

  /// Attachment-capture seam (camera/gallery → pre-signed upload contract).
  final AttachmentService attachmentService;

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

  /// Resolves the durable outbox's backing file under the platform's
  /// app-support directory (private to the app, not user-visible like Documents,
  /// and not auto-cleared like the cache dir — the queue must persist until it
  /// syncs). Created lazily on the first outbox write.
  static Future<File> _resolveOutboxFile() async {
    final dir = await getApplicationSupportDirectory();
    return File('${dir.path}/taarifu_outbox.json');
  }

  /// Resolves the settings file under the same app-support directory (small,
  /// non-sensitive record; survives cold start like the outbox).
  static Future<File> _resolveSettingsFile() async {
    final dir = await getApplicationSupportDirectory();
    return File('${dir.path}/taarifu_settings.json');
  }
}
