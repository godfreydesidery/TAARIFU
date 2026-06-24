/// The single HTTP entry point for the app: a configured [Dio] plus envelope
/// unwrapping and typed error mapping.
///
/// Responsibility: own one [Dio] (base URL, 2G/3G-tuned timeouts, gzip, bearer
/// auth, bounded retry-with-backoff) and expose `get`/`post` that return the
/// *already-unwrapped, typed* `data` payload — or throw a typed [ApiException].
/// Every feature data source goes through here, so there is one place to evolve
/// transport policy (PRD §15, §17). No widget or BLoC ever touches `Dio`.
library;

import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kIsWeb;

import '../config/app_config.dart';
import '../storage/token_store.dart';
import 'api_exception.dart';
import 'api_response.dart';
import 'connectivity_service.dart';

/// A decoded success payload, paired with optional pagination metadata.
class ApiResult<T> {
  /// Creates a result.
  const ApiResult({required this.data, this.meta});

  /// The decoded payload.
  final T data;

  /// Pagination metadata for list endpoints, or `null`.
  final PageMeta? meta;
}

/// Wraps [Dio] to talk to the Taarifu backend and return typed results.
class ApiClient {
  /// Builds the client and configures the underlying [Dio].
  ///
  /// [config] supplies the base URL; [tokenStore] provides the bearer token;
  /// [connectivity] gives a fast offline pre-check; [dio] is injectable so tests
  /// can supply a mock-adapter-backed instance.
  ApiClient({
    required AppConfig config,
    required TokenStore tokenStore,
    required ConnectivityService connectivity,
    Dio? dio,
  }) : _tokenStore = tokenStore,
       _connectivity = connectivity,
       _dio = dio ?? Dio() {
    _dio.options
      ..baseUrl = config.apiBaseUrl
      // Timeouts tuned for patchy 2G/3G — long enough to survive a slow link,
      // short enough not to hang a citizen on a dead one (PRD §15).
      ..connectTimeout = const Duration(seconds: 15)
      ..receiveTimeout = const Duration(seconds: 30)
      ..sendTimeout = const Duration(seconds: 30)
      ..responseType = ResponseType.json
      // The envelope itself carries success/failure; let us inspect non-2xx
      // bodies (e.g. 403 TIER_TOO_LOW) rather than have Dio throw first.
      ..validateStatus = (_) => true;

    // Request gzip explicitly on NATIVE platforms (Android/iOS) to shrink
    // payloads on a tight data budget (PRD §15). On Flutter WEB the browser owns
    // content-negotiation and FORBIDS scripts from setting `Accept-Encoding`
    // (it throws "Refused to set unsafe header"); the browser already negotiates
    // gzip/br itself, so we simply skip the header there. WHY guard rather than
    // drop: the citizen app's real target is low-end Android over 2G/3G, where the
    // explicit gzip request still matters; web is the secondary PWA surface.
    if (!kIsWeb) {
      _dio.options.headers[HttpHeaders.acceptEncodingHeader] = 'gzip';
    }

    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          final token = await _tokenStore.readAccessToken();
          if (token != null && token.isNotEmpty) {
            options.headers[HttpHeaders.authorizationHeader] = 'Bearer $token';
          }
          handler.next(options);
        },
      ),
    );
  }

  final Dio _dio;
  final TokenStore _tokenStore;
  final ConnectivityService _connectivity;

  /// Maximum automatic retries for transient transport failures.
  static const int _maxRetries = 2;

  /// Performs a GET and returns the unwrapped, typed payload.
  ///
  /// [path] is relative to the configured base URL. [query] are URL params.
  /// [parser] decodes the raw `data` node into `T`. Throws [OfflineException],
  /// [TimeoutException], [ApiErrorException], or [UnknownApiException].
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) => _send(() => _dio.get(path, queryParameters: query), parser);

  /// Performs a POST and returns the unwrapped, typed payload.
  ///
  /// [body] is the JSON request body; [idempotencyKey], when supplied, is sent
  /// as the `Idempotency-Key` header so a retried mutation is de-duplicated by
  /// the backend (ARCHITECTURE §5.4) — essential for offline submit/replay.
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) {
    final options = idempotencyKey == null
        ? null
        : Options(headers: {'Idempotency-Key': idempotencyKey});
    return _send(() => _dio.post(path, data: body, options: options), parser);
  }

  /// Performs a PUT and returns the unwrapped, typed payload.
  ///
  /// Used for idempotent upserts (e.g. a notification preference keyed by
  /// `(type, channel)` — `PUT /notification-preferences`). Like [post] it accepts
  /// an optional [idempotencyKey], though a PUT is naturally idempotent.
  Future<ApiResult<T>> put<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) {
    final options = idempotencyKey == null
        ? null
        : Options(headers: {'Idempotency-Key': idempotencyKey});
    return _send(() => _dio.put(path, data: body, options: options), parser);
  }

  /// Shared send pipeline: offline pre-check → retrying call → envelope unwrap.
  Future<ApiResult<T>> _send<T>(
    Future<Response<dynamic>> Function() call,
    T Function(Object? data) parser,
  ) async {
    if (!await _connectivity.isProbablyOnline) {
      throw const OfflineException();
    }
    final response = await _callWithRetry(call);
    return _unwrap(response, parser);
  }

  /// Executes [call] with bounded exponential backoff for transient failures.
  ///
  /// WHY retry only transient classes: connect/receive timeouts and connection
  /// errors are worth a retry on a flaky link; a 4xx domain error is not (it
  /// will fail identically). Backoff is short to respect the data/battery budget.
  Future<Response<dynamic>> _callWithRetry(
    Future<Response<dynamic>> Function() call,
  ) async {
    var attempt = 0;
    while (true) {
      try {
        return await call();
      } on DioException catch (e) {
        final transient =
            e.type == DioExceptionType.connectionTimeout ||
            e.type == DioExceptionType.receiveTimeout ||
            e.type == DioExceptionType.sendTimeout ||
            e.type == DioExceptionType.connectionError;
        if (!transient || attempt >= _maxRetries) {
          if (e.type == DioExceptionType.connectionTimeout ||
              e.type == DioExceptionType.receiveTimeout ||
              e.type == DioExceptionType.sendTimeout) {
            throw const TimeoutException();
          }
          if (e.type == DioExceptionType.connectionError) {
            throw const OfflineException('Connection error');
          }
          throw UnknownApiException(e.message ?? 'Transport error');
        }
        attempt++;
        await Future<void>.delayed(Duration(milliseconds: 400 * attempt));
      }
    }
  }

  /// Decodes a raw [Response] into a typed [ApiResult] or throws a typed error.
  ApiResult<T> _unwrap<T>(
    Response<dynamic> response,
    T Function(Object? data) parser,
  ) {
    final raw = response.data;
    if (raw is! Map<String, dynamic>) {
      // A body that is not our envelope (e.g. a bare 502 HTML page) → unknown.
      throw const UnknownApiException('Malformed response (no envelope)');
    }
    final envelope = ApiResponse<T>.fromJson(raw, parser);
    if (!envelope.success) {
      throw ApiErrorException(
        statusCode: envelope.statusCode,
        error: envelope.error ?? const ApiError(code: 'UNKNOWN'),
        serverMessage: envelope.message,
      );
    }
    return ApiResult<T>(data: envelope.data as T, meta: envelope.meta);
  }
}
