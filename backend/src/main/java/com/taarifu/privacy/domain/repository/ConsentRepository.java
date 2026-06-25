package com.taarifu.privacy.domain.repository;

import com.taarifu.privacy.domain.model.Consent;
import com.taarifu.privacy.domain.model.enums.ConsentPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link Consent} (ARCHITECTURE.md §3.3; ADR-0016 §2).
 *
 * <p>Responsibility: resolve "the current consent for this subject + purpose" (the single non-superseded
 * row) and list a subject's consent ledger for the privacy center. The {@code @SQLRestriction} on
 * {@link Consent} already hides soft-deleted rows; these queries add the not-superseded filter so callers
 * see only the live decision per purpose.</p>
 */
public interface ConsentRepository extends JpaRepository<Consent, Long> {

    /**
     * The current (non-superseded) consent decision for a subject + purpose, if any.
     *
     * @param subjectPublicId the consenting account's public id.
     * @param purpose         the processing purpose.
     * @return the single live decision row, or empty if the subject never decided this purpose.
     */
    Optional<Consent> findBySubjectPublicIdAndPurposeAndSupersededFalse(UUID subjectPublicId,
                                                                        ConsentPurpose purpose);

    /**
     * The subject's current consent decisions across all purposes (one live row per decided purpose) —
     * backs the privacy-center read and the export's consent section.
     *
     * @param subjectPublicId the consenting account's public id.
     * @return the live decision rows (never {@code null}; empty if the subject has decided nothing).
     */
    List<Consent> findBySubjectPublicIdAndSupersededFalse(UUID subjectPublicId);
}
