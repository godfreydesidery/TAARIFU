package com.taarifu.accountability.application.service;

import com.taarifu.accountability.api.dto.AccountabilityExportView;
import com.taarifu.accountability.domain.model.Rating;
import com.taarifu.accountability.domain.repository.RatingRepository;
import com.taarifu.privacy.api.SubjectExportContributor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Accountability's contribution to a data-subject ACCESS export — the ratings the subject submitted
 * (PRD §18 PDPA right of access, UC-A16/UC-S09; ADR-0016 §4).
 *
 * <p>Responsibility: implements the privacy module's {@link SubjectExportContributor} SPI so the privacy
 * export aggregator can include accountability's data <b>without</b> reaching into accountability's internals
 * (ADR-0013). Registered automatically as a Spring bean; the privacy {@code SubjectDataExportService} injects
 * every contributor and composes the export by {@link #section()}.</p>
 *
 * <p><b>🔒 Data-minimisation (PRD §18, ADR-0016 §4):</b> returns {@link AccountabilityExportView} — only the
 * ratings the subject themselves gave (score, their own comment, the rated subject and period). It never
 * exposes other raters' rows or the aggregate of another subject. No token balance is read on this path (the
 * civic-integrity fence, §23).</p>
 */
@Service
public class AccountabilityExportContributor implements SubjectExportContributor {

    /** The export section key accountability fills. */
    private static final String SECTION = "ratings";

    private final RatingRepository ratingRepository;

    /**
     * @param ratingRepository the subject's submitted ratings.
     */
    public AccountabilityExportContributor(RatingRepository ratingRepository) {
        this.ratingRepository = ratingRepository;
    }

    /** {@inheritDoc} */
    @Override
    public String section() {
        return SECTION;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the subject's submitted ratings, or {@code null} if the subject rated nothing (so the section
     * is simply absent from the export).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Object contribute(UUID subjectPublicId) {
        List<Rating> ratings = ratingRepository.findByRaterProfileId(subjectPublicId);
        if (ratings.isEmpty()) {
            return null;
        }
        List<AccountabilityExportView.SubmittedRating> items = ratings.stream()
                .map(r -> new AccountabilityExportView.SubmittedRating(
                        r.getPublicId(),
                        r.getSubjectType().name(),
                        r.getSubjectId(),
                        r.getScore(),
                        r.getComment(),
                        r.getPeriod(),
                        r.getCreatedAt()))
                .toList();
        return new AccountabilityExportView(items);
    }
}
