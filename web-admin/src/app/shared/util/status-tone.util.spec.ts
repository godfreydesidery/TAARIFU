import { statusTone } from './status-tone.util';

/**
 * Unit tests for the shared {@link statusTone} mapping — guards the design-system contract that every
 * status badge across the console (reports, responders, moderation, users) colours a given status the
 * same way. Pure function, no DI: fast and deterministic.
 */
describe('statusTone', () => {
  it('maps terminal/positive statuses to success', () => {
    expect(statusTone('RESOLVED')).toBe('success');
    expect(statusTone('ACTIVE')).toBe('success');
    expect(statusTone('CONFIRMED')).toBe('success');
  });

  it('maps in-flight statuses to info', () => {
    expect(statusTone('IN_PROGRESS')).toBe('info');
    expect(statusTone('ASSIGNED')).toBe('info');
  });

  it('maps needs-attention statuses to warning', () => {
    expect(statusTone('PENDING')).toBe('warning');
    expect(statusTone('SUBMITTED')).toBe('warning');
    expect(statusTone('ESCALATED')).toBe('warning');
  });

  it('maps failure/breach statuses to danger', () => {
    expect(statusTone('REJECTED')).toBe('danger');
    expect(statusTone('SUSPENDED')).toBe('danger');
    expect(statusTone('URGENT')).toBe('danger');
    expect(statusTone('CRITICAL')).toBe('danger');
  });

  it('is case-insensitive', () => {
    expect(statusTone('resolved')).toBe('success');
    expect(statusTone('Pending')).toBe('warning');
  });

  it('falls back to neutral for unknown/empty tokens', () => {
    expect(statusTone('SOMETHING_NEW')).toBe('neutral');
    expect(statusTone('')).toBe('neutral');
    expect(statusTone(null)).toBe('neutral');
    expect(statusTone(undefined)).toBe('neutral');
  });
});
