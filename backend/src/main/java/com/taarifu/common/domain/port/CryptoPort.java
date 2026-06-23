package com.taarifu.common.domain.port;

/**
 * Outbound port for field-level (envelope) encryption of PII at rest (PRD §18, EI-19,
 * ARCHITECTURE.md §4.3, §7).
 *
 * <p>Responsibility: abstracts "encrypt/decrypt this sensitive value" so PII columns — above all
 * {@code Profile.idNo} (national/voter ID) — are stored as <b>ciphertext</b> and only decrypted when
 * lawfully needed (PDPA 2022/2023). The production adapter uses cloud KMS envelope encryption; a dev
 * adapter uses a local key. Callers never see the key material.</p>
 *
 * <p>WHY a port (not direct crypto calls in the entity/converter): it lets the KMS provider be swapped
 * and isolates the degradation mode (leased keys cached; new-PII decrypt blocked but the system stays
 * up — EI-19) without touching the domain (ADR-0004). Dedup never requires decryption — the separate
 * blind-index {@code idHash} drives lookups (D15), so this port is invoked only for genuine
 * read-the-value cases.</p>
 */
public interface CryptoPort {

    /**
     * Encrypts a plaintext PII value for storage.
     *
     * @param plaintext the sensitive value (e.g. a national ID number); {@code null} returns {@code null}.
     * @return the ciphertext to persist.
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a stored ciphertext back to plaintext.
     *
     * @param ciphertext the stored value; {@code null} returns {@code null}.
     * @return the plaintext; must never be logged (PRD §18 — PII redaction).
     */
    String decrypt(String ciphertext);

    /**
     * Computes a stable, non-reversible <b>blind index</b> over a normalised value for equality-based
     * dedup lookups without decryption (D15, PRD §18).
     *
     * @param value the value to index (e.g. {@code idType + ":" + idNo}).
     * @return a deterministic hash suitable for a unique index; the same input always yields the same
     *         output, but the input cannot be recovered from it.
     */
    String blindIndex(String value);
}
