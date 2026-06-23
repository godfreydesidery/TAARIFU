/// Wire models for the identity/profile endpoints, mirroring the backend DTOs in
/// `com.taarifu.identity.api.dto` (ProfileController, ProfileLocationController,
/// VerificationController).
library;

/// The caller's own account+profile snapshot (backend `MeDto`, `GET /profiles/me`).
///
/// The owner reads themselves, so their own phone/email are present; this never
/// carries `idNo` or any other user's PII (PRD §18). [tier] is the live trust
/// tier used as a UI hint for gating (T0–T3).
class Me {
  /// Creates a snapshot.
  const Me({
    required this.userPublicId,
    required this.tier,
    this.phone,
    this.firstName,
    this.lastName,
    this.email,
    this.phoneVerified = false,
    this.emailVerified = false,
    this.idVerified = false,
  });

  /// The account public id (UUID string).
  final String userPublicId;

  /// The live trust tier name (`T0`/`T1`/`T2`/`T3`).
  final String tier;

  /// The caller's own phone, or `null`.
  final String? phone;

  /// Given/first name, or `null`.
  final String? firstName;

  /// Family name, or `null`.
  final String? lastName;

  /// The caller's own email, or `null`.
  final String? email;

  /// Whether the phone is verified.
  final bool phoneVerified;

  /// Whether the email is verified.
  final bool emailVerified;

  /// Whether the government ID is verified.
  final bool idVerified;

  /// True when the tier is at least T2 (can file binding reports, ask Q&A).
  bool get isT2OrAbove => _rank(tier) >= 2;

  /// True when the tier is T3 (can sign petitions, respond to binding polls).
  bool get isT3 => _rank(tier) >= 3;

  static int _rank(String t) {
    switch (t) {
      case 'T3':
        return 3;
      case 'T2':
        return 2;
      case 'T1':
        return 1;
      default:
        return 0;
    }
  }

  /// Parses a `MeDto` node.
  factory Me.fromJson(Map<String, dynamic> json) => Me(
    userPublicId: json['userPublicId'] as String? ?? '',
    tier: json['tier'] as String? ?? 'T1',
    phone: json['phone'] as String?,
    firstName: json['firstName'] as String?,
    lastName: json['lastName'] as String?,
    email: json['email'] as String?,
    phoneVerified: json['phoneVerified'] == true,
    emailVerified: json['emailVerified'] == true,
    idVerified: json['idVerified'] == true,
  );
}
