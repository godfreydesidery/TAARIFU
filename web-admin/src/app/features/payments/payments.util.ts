/**
 * Pure helpers for the payments admin view. Centralises MSISDN masking so the console NEVER renders a raw
 * mobile number even if a backend row arrives unmasked (defence in depth for PII; PRD §18, DI5).
 */

/**
 * Masks an MSISDN for display, keeping only the country/prefix and the last 4 digits.
 *
 * <p>WHY a client-side mask even though the backend is expected to mask: defence in depth. A payment row's
 * MSISDN is sensitive PII; if any environment/endpoint returns it unmasked, the console must still not leak
 * it (PRD §18 PII-redaction, DI5 MSISDN minimisation). A value that is already masked (contains `*`) or too
 * short to mask is returned unchanged.</p>
 *
 * @param msisdn the raw or already-masked number, or `null`.
 * @returns the masked number (e.g. `+2557****1234`), or `null` if input was `null`.
 */
export function maskMsisdn(msisdn: string | null | undefined): string | null {
  if (!msisdn) {
    return null;
  }
  // Already masked, or no realistic digits to mask — leave as-is.
  if (msisdn.includes('*')) {
    return msisdn;
  }
  const digits = msisdn.replace(/[^\d]/g, '');
  if (digits.length <= 6) {
    return msisdn;
  }
  const prefix = msisdn.startsWith('+') ? '+' : '';
  const head = digits.slice(0, 4);
  const tail = digits.slice(-4);
  return `${prefix}${head}****${tail}`;
}
