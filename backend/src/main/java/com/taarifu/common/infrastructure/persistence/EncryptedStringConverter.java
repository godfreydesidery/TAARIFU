package com.taarifu.common.infrastructure.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that transparently encrypts a {@code String} entity attribute to
 * ciphertext on write and decrypts it on read (PRD §18, EI-19, ARCHITECTURE.md §4.3).
 *
 * <p>Responsibility: applied via {@code @Convert} to PII columns (above all {@code Profile.idNo}) so the
 * <b>database only ever stores ciphertext</b> while application code sees plaintext. Delegates the
 * actual crypto to the {@link com.taarifu.common.domain.port.CryptoPort} reached through
 * {@link CryptoConverterSupport} (Hibernate instantiates converters outside Spring).</p>
 *
 * <p>WHY {@code autoApply = false}: encryption is opt-in per field (only true PII), not blanket on
 * every {@code String} — applying it everywhere would be wasteful and would break indexing/search on
 * non-sensitive columns. Fields opt in with {@code @Convert(converter = EncryptedStringConverter.class)}.</p>
 *
 * <p>Note: an encrypted column is randomised (GCM IV per write), so it is <b>not</b> directly
 * searchable by equality — dedup uses the separate deterministic blind-index column, never this one
 * (D15).</p>
 */
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /**
     * @param attribute the plaintext value held in the entity.
     * @return the ciphertext to store, or {@code null} for a {@code null} attribute.
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return CryptoConverterSupport.cryptoPort().encrypt(attribute);
    }

    /**
     * @param dbData the stored ciphertext.
     * @return the decrypted plaintext, or {@code null} for {@code null} data. Never log the result
     *         (PII redaction, PRD §18).
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        return CryptoConverterSupport.cryptoPort().decrypt(dbData);
    }
}
