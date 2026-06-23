/// Tests the envelope decoder against the exact backend shapes:
/// `{ success, statusCode, message, data, meta, timestamp }`, machine code at
/// `data.code`, field errors at `data.errors[]`.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_response.dart';

void main() {
  group('ApiResponse.fromJson', () {
    test('decodes a success envelope and parses the data payload', () {
      final json = <String, dynamic>{
        'success': true,
        'statusCode': 200,
        'message': 'Imefaulu',
        'data': {'challengeId': 'abc-123'},
        'timestamp': '2026-06-23T09:00:00Z',
      };

      final res = ApiResponse<String>.fromJson(
        json,
        (data) => (data! as Map<String, dynamic>)['challengeId'] as String,
      );

      expect(res.success, isTrue);
      expect(res.statusCode, 200);
      expect(res.message, 'Imefaulu');
      expect(res.data, 'abc-123');
      expect(res.error, isNull);
    });

    test('decodes an error envelope: machine code at data.code', () {
      final json = <String, dynamic>{
        'success': false,
        'statusCode': 403,
        'message': 'Hadhi yako haitoshi',
        'data': {'code': 'TIER_TOO_LOW'},
      };

      final res = ApiResponse<Object>.fromJson(json, (data) => data!);

      expect(res.success, isFalse);
      expect(res.data, isNull);
      expect(res.error, isNotNull);
      expect(res.error!.code, 'TIER_TOO_LOW');
      expect(res.error!.errors, isEmpty);
    });

    test('decodes field errors at data.errors[] for validation failures', () {
      final json = <String, dynamic>{
        'success': false,
        'statusCode': 400,
        'message': 'Validation failed',
        'data': {
          'code': 'VALIDATION_FAILED',
          'errors': [
            {
              'field': 'phone',
              'code': 'identity.phone.invalid',
              'message': 'Invalid',
            },
          ],
        },
      };

      final res = ApiResponse<Object>.fromJson(json, (data) => data!);

      expect(res.error!.code, 'VALIDATION_FAILED');
      expect(res.error!.errors, hasLength(1));
      expect(res.error!.errors.first.field, 'phone');
      expect(res.error!.errors.first.code, 'identity.phone.invalid');
    });

    test('decodes pagination meta', () {
      final json = <String, dynamic>{
        'success': true,
        'statusCode': 200,
        'message': 'OK',
        'data': <dynamic>[],
        'meta': {'page': 0, 'size': 20, 'total': 137, 'totalPages': 7},
      };

      final res = ApiResponse<List<dynamic>>.fromJson(
        json,
        (data) => (data as List?) ?? const [],
      );

      expect(res.meta, isNotNull);
      expect(res.meta!.total, 137);
      expect(res.meta!.totalPages, 7);
      expect(res.meta!.hasMore, isTrue);
    });
  });
}
