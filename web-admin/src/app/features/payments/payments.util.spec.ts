import { Payment, PaymentStatus } from './payments.models';
import { buildTimeline, canRefund, canVoid, maskMsisdn, minorToMajor } from './payments.util';

/**
 * Unit tests for the pure payments helpers — the refund/void state-machine gates, the derived status
 * timeline, minor→major money conversion, and defensive MSISDN masking. Pure functions, no DI: fast and
 * deterministic. These guard the operator-facing rules: refund only a SETTLED top-up, void only an
 * UN-SETTLED one (mirroring the backend `TopUpStatus`), and never invent transitions the DTO can't prove.
 */
describe('payments.util', () => {
  /** Builds a {@link Payment} with sensible defaults, overridable per test. */
  function payment(overrides: Partial<Payment> = {}): Payment {
    return {
      id: 'pay-1',
      buyerId: 'buyer-1',
      status: 'SUCCEEDED' as PaymentStatus,
      provider: 'MPESA',
      providerRef: 'REF-1',
      amountMinor: 100000,
      tokenAmount: 50,
      currency: 'TZS',
      failureReason: null,
      reversalReason: null,
      createdAt: '2026-06-01T10:00:00Z',
      updatedAt: '2026-06-01T10:05:00Z',
      ...overrides,
    };
  }

  describe('minorToMajor', () => {
    it('divides minor units by 100', () => {
      expect(minorToMajor(123456)).toBe(1234.56);
      expect(minorToMajor(100)).toBe(1);
    });

    it('returns 0 for null/undefined/NaN', () => {
      expect(minorToMajor(null)).toBe(0);
      expect(minorToMajor(undefined)).toBe(0);
      expect(minorToMajor(NaN)).toBe(0);
    });
  });

  describe('canRefund', () => {
    it('is true only for a SUCCEEDED top-up', () => {
      expect(canRefund(payment({ status: 'SUCCEEDED' }))).toBe(true);
    });

    it('is false for any non-settled state', () => {
      for (const status of ['INITIATED', 'PENDING', 'FAILED', 'VOIDED', 'REFUNDED'] as PaymentStatus[]) {
        expect(canRefund(payment({ status }))).toBe(false);
      }
      expect(canRefund(null)).toBe(false);
    });
  });

  describe('canVoid', () => {
    it('is true for an un-settled INITIATED or PENDING attempt', () => {
      expect(canVoid(payment({ status: 'INITIATED' }))).toBe(true);
      expect(canVoid(payment({ status: 'PENDING' }))).toBe(true);
    });

    it('is false once settled or terminal', () => {
      for (const status of ['SUCCEEDED', 'FAILED', 'VOIDED', 'REFUNDED'] as PaymentStatus[]) {
        expect(canVoid(payment({ status }))).toBe(false);
      }
      expect(canVoid(null)).toBe(false);
    });
  });

  describe('buildTimeline', () => {
    it('returns [] for no payment', () => {
      expect(buildTimeline(null)).toEqual([]);
    });

    it('shows a single current INITIATED step for a fresh attempt', () => {
      const steps = buildTimeline(payment({ status: 'INITIATED' }));
      expect(steps.length).toBe(1);
      expect(steps[0]).toEqual(
        jasmine.objectContaining({ status: 'INITIATED', current: true, reason: null }),
      );
    });

    it('shows INITIATED then the current state for a progressed attempt', () => {
      const steps = buildTimeline(payment({ status: 'SUCCEEDED' }));
      expect(steps.length).toBe(2);
      expect(steps[0]).toEqual(jasmine.objectContaining({ status: 'INITIATED', current: false }));
      expect(steps[1]).toEqual(jasmine.objectContaining({ status: 'SUCCEEDED', current: true }));
    });

    it('surfaces the reversal reason on a REFUNDED step (redacted machine code, no PII)', () => {
      const steps = buildTimeline(payment({ status: 'REFUNDED', reversalReason: 'DUPLICATE_CHARGE' }));
      expect(steps[1]).toEqual(
        jasmine.objectContaining({ status: 'REFUNDED', reason: 'DUPLICATE_CHARGE', current: true }),
      );
    });

    it('surfaces the failure reason on a FAILED step', () => {
      const steps = buildTimeline(payment({ status: 'FAILED', reversalReason: null, failureReason: 'TIMEOUT' }));
      expect(steps[1]).toEqual(jasmine.objectContaining({ status: 'FAILED', reason: 'TIMEOUT' }));
    });
  });

  describe('maskMsisdn', () => {
    it('masks a raw number to prefix + last 4 (defence in depth, PRD §18)', () => {
      expect(maskMsisdn('+255712345678')).toBe('+2557****5678');
    });

    it('leaves an already-masked or null value unchanged', () => {
      expect(maskMsisdn('+2557****5678')).toBe('+2557****5678');
      expect(maskMsisdn(null)).toBeNull();
    });
  });
});
