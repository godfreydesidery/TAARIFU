/// bloc_test coverage for [EngagementCubit] focusing on the client-side tier
/// gate (the integrity fence's UX half): signing a petition below T3 must be
/// blocked locally with a `tierTooLow` action and MUST NOT hit the network
/// (the server still enforces the gate authoritatively — this is just a fast,
/// clear UX guard). At T3 the sign call goes through.
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/features/engagement/bloc/engagement_cubit.dart';
import 'package:taarifu_citizen/features/engagement/data/engagement_models.dart';
import 'package:taarifu_citizen/features/engagement/data/engagement_repository.dart';
import 'package:taarifu_citizen/features/profile/data/profile_repository.dart';

/// Fake engagement repo: records whether sign was called; returns a stub.
class _FakeEngagementRepository implements EngagementRepository {
  bool signCalled = false;

  @override
  Future<Petition> signPetition(
    String petitionId, {
    String? comment,
    bool publicSignature = false,
  }) async {
    signCalled = true;
    return Petition(
      id: petitionId,
      title: 'x',
      status: 'ACTIVE',
      signatureCount: 1,
      signatureGoal: 10,
    );
  }

  @override
  Future<List<Petition>> listPetitions() async => const [];
  @override
  Future<List<Survey>> listSurveys() async => const [];
  @override
  Future<List<Question>> listQuestions({String? targetRepId}) async => const [];
  @override
  Future<Survey> respondToSurvey(String surveyId, {required String answers}) =>
      throw UnimplementedError();
  @override
  Future<Question> askQuestion({
    required String targetRepId,
    required String body,
  }) => throw UnimplementedError();
}

/// Fake profile repo whose tier is configurable.
class _FakeProfileRepository implements ProfileRepository {
  _FakeProfileRepository(this.tier);
  final String tier;

  @override
  Future<String> getTier() async => tier;

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError();
}

void main() {
  group('EngagementCubit tier gate', () {
    late _FakeEngagementRepository engagement;

    setUp(() => engagement = _FakeEngagementRepository());

    blocTest<EngagementCubit, EngagementState>(
      'sign below T3 → tierTooLow and no network call',
      build: () => EngagementCubit(
        repository: engagement,
        profileRepository: _FakeProfileRepository('T2'),
      ),
      seed: () => const EngagementState(tier: 'T2'),
      act: (cubit) => cubit.signPetition('p-1'),
      expect: () => [
        isA<EngagementState>().having(
          (s) => s.action,
          'action',
          EngagementAction.tierTooLow,
        ),
      ],
      verify: (_) => expect(engagement.signCalled, isFalse),
    );

    blocTest<EngagementCubit, EngagementState>(
      'sign at T3 → calls repository and reports signed',
      build: () => EngagementCubit(
        repository: engagement,
        profileRepository: _FakeProfileRepository('T3'),
      ),
      seed: () => const EngagementState(
        tier: 'T3',
        petitions: [
          Petition(id: 'p-1', title: 'x', status: 'ACTIVE', signatureGoal: 10),
        ],
      ),
      act: (cubit) => cubit.signPetition('p-1'),
      expect: () => [
        isA<EngagementState>().having(
          (s) => s.actionInFlight,
          'actionInFlight',
          true,
        ),
        isA<EngagementState>()
            .having((s) => s.action, 'action', EngagementAction.signed)
            .having((s) => s.actionInFlight, 'actionInFlight', false),
      ],
      verify: (_) => expect(engagement.signCalled, isTrue),
    );
  });
}
