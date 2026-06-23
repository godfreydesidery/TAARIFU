package com.taarifu.common.infrastructure.adapter;

import com.taarifu.common.domain.port.CryptoPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Dev/local {@link CryptoPort} adapter using AES-GCM with a single configured key (ADR-0004,
 * FOUNDATION-SCOPE.md §5, PRD §18).
 *
 * <p>Responsibility: provides a working, non-stub encryption implementation so PII columns are genuinely
 * encrypted at rest in dev/test and the schema/column shapes are real. The key is injected from
 * configuration/environment ({@code taarifu.security.crypto.dev-key}) — <b>never hard-coded</b>
 * (CLAUDE.md §12). In production this adapter is replaced by a KMS-envelope adapter behind the same
 * port (EI-19) with <b>no change to entities or converters</b>.</p>
 *
 * <p>WHY AES-GCM for confidentiality + HMAC-SHA-256 for the blind index: GCM gives authenticated
 * encryption for the stored ciphertext; the blind index must be <b>deterministic</b> (same input →
 * same output) to back a unique dedup index (D15), so it uses a keyed HMAC, not the randomised cipher.
 * The two use distinct derivations of the configured key.</p>
 *
 * <p>WHY this is acceptable as the dev default (and what production changes): a single static key
 * cannot be rotated and is not envelope-encrypted; production must use KMS (EI-19). This adapter exists
 * to make the encrypted-column contract real and testable now, per the foundation scope.</p>
 */
@Component
public class DevKeyCryptoAdapter implements CryptoPort {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final String HMAC_ALGO = "HmacSHA256";

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec indexKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param devKey base64-encoded 256-bit key from {@code taarifu.security.crypto.dev-key}
     *               (env-provided; never committed). Two independent sub-keys are derived from it via
     *               domain-separated SHA-256 so encryption and blind-index keys differ.
     */
    public DevKeyCryptoAdapter(@Value("${taarifu.security.crypto.dev-key}") String devKey) {
        byte[] raw = Base64.getDecoder().decode(devKey);
        this.encryptionKey = new SecretKeySpec(sha256("enc:", raw), "AES");
        this.indexKey = new SecretKeySpec(sha256("idx:", raw), HMAC_ALGO);
    }

    /** {@inheritDoc} */
    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prefix the IV so decrypt can recover it; the whole blob is base64-encoded for the column.
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // Never include the plaintext in the message (PII redaction, PRD §18).
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String blindIndex(String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(indexKey);
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Blind-index computation failed", e);
        }
    }

    /** Domain-separated key derivation so the AES and HMAC keys are independent. */
    private static byte[] sha256(String domain, byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(domain.getBytes(StandardCharsets.UTF_8));
            return md.digest(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Key derivation failed", e);
        }
    }
}
