/// Tests the report-form attachment seam on [ReportFormCubit] (US-3.1, EI-8):
/// a successful capture appends a [PendingAttachment]; an unavailable picker
/// surfaces a non-fatal error (the report is still filable); and a submit carries
/// the captured refs into the draft body (local refs until the upload endpoint
/// lands — the seam is preserved, nothing faked).
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/core/storage/json_cache.dart';
import 'package:taarifu_citizen/core/storage/outbox_store.dart';
import 'package:taarifu_citizen/features/reporting/bloc/report_form_cubit.dart';
import 'package:taarifu_citizen/features/reporting/data/attachment_models.dart';
import 'package:taarifu_citizen/features/reporting/data/attachment_service.dart';
import 'package:taarifu_citizen/features/reporting/data/category_repository.dart';
import 'package:taarifu_citizen/features/reporting/data/report_repository.dart';
import 'package:taarifu_citizen/features/reporting/data/reporting_models.dart';

/// Fake [ApiClient] that records POST bodies so the test can assert the report
/// body carried the attachment refs.
class _FakeApiClient implements ApiClient {
  final List<Object?> postBodies = [];

  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async {
    postBodies.add(body);
    final data = <String, dynamic>{
      'id': 'r-1',
      'code': 'TAR-2026-000001',
      'title': 'x',
      'status': 'NEW',
    };
    return ApiResult<T>(data: parser(data));
  }

  @override
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async => ApiResult<T>(data: parser(const <Map<String, dynamic>>[]));

  @override
  Future<ApiResult<T>> put<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();

  @override
  Future<ApiResult<T>> delete<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();
}

/// A fake picker that returns a fixed captured image.
class _FakePicker implements AttachmentService {
  @override
  bool get isAvailable => true;

  @override
  Future<PendingAttachment> capture(AttachmentSource source) async =>
      const PendingAttachment(
        localPath: '/tmp/photo.jpg',
        filename: 'photo.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 1234,
      );
}

const _draft = ReportDraft(
  categoryId: 'c-1',
  title: 'Maji yamekatika',
  description: 'Hakuna maji',
  wardId: 'w-1',
);

void main() {
  late _FakeApiClient api;
  late ReportRepository reports;
  late CategoryRepository categories;

  setUp(() {
    api = _FakeApiClient();
    reports = ReportRepository(apiClient: api, outbox: InMemoryOutboxStore());
    categories = CategoryRepository(
      apiClient: api,
      cache: InMemoryJsonCache(),
    );
  });

  test('capture appends a held attachment', () async {
    final cubit = ReportFormCubit(
      categoryRepository: categories,
      reportRepository: reports,
      attachmentService: _FakePicker(),
    );
    expect(cubit.attachmentsAvailable, isTrue);
    await cubit.addAttachment(AttachmentSource.camera);
    expect(cubit.state.attachments, hasLength(1));
    expect(cubit.state.attachments.single.filename, 'photo.jpg');
    expect(cubit.state.attachmentError, isNull);
    await cubit.close();
  });

  test('the default (unwired) picker surfaces a non-fatal error', () async {
    final cubit = ReportFormCubit(
      categoryRepository: categories,
      reportRepository: reports,
    );
    expect(cubit.attachmentsAvailable, isFalse);
    await cubit.addAttachment(AttachmentSource.gallery);
    expect(cubit.state.attachments, isEmpty);
    expect(cubit.state.attachmentError, isA<AttachmentUnavailable>());
    await cubit.close();
  });

  test('removeAttachment drops the held item', () async {
    final cubit = ReportFormCubit(
      categoryRepository: categories,
      reportRepository: reports,
      attachmentService: _FakePicker(),
    );
    await cubit.addAttachment(AttachmentSource.camera);
    cubit.removeAttachment('/tmp/photo.jpg');
    expect(cubit.state.attachments, isEmpty);
    await cubit.close();
  });

  test('submit carries the captured refs into the report body', () async {
    final cubit = ReportFormCubit(
      categoryRepository: categories,
      reportRepository: reports,
      attachmentService: _FakePicker(),
    );
    await cubit.addAttachment(AttachmentSource.camera);
    await cubit.submit(_draft);
    expect(cubit.state.status, ReportFormStatus.filed);
    final body = api.postBodies.single! as Map<String, dynamic>;
    final refs = body['attachmentRefs'] as List;
    // Before the upload endpoint lands the ref is a local: placeholder (seam).
    expect(refs.single, 'local:/tmp/photo.jpg');
    await cubit.close();
  });
}
