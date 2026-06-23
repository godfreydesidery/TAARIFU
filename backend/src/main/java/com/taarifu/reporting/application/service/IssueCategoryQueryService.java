package com.taarifu.reporting.application.service;

import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.IssueCategoryQueryApi;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reporting's implementation of the published {@link IssueCategoryQueryApi} — the synchronous
 * {@code responders → reporting} category-validation seam (ADR-0013 §1, §4a).
 *
 * <p>Responsibility: answer "is this issue category valid?" so the responders module never configures a
 * {@code RoutingRule}/{@code Responder} against a non-existent category, without importing reporting's
 * internals. {@code @Transactional(readOnly = true)}; returns only {@code void}/{@code boolean}.</p>
 */
@Service
@Transactional(readOnly = true)
public class IssueCategoryQueryService implements IssueCategoryQueryApi {

    private final IssueCategoryRepository categoryRepository;

    /**
     * @param categoryRepository category persistence port (existence checks).
     */
    public IssueCategoryQueryService(IssueCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** {@inheritDoc} */
    @Override
    public void requireCategory(UUID categoryPublicId) {
        if (!exists(categoryPublicId)) {
            throw new ResourceNotFoundException("reporting.category.notFound", categoryPublicId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(UUID categoryPublicId) {
        return categoryPublicId != null && categoryRepository.findByPublicId(categoryPublicId).isPresent();
    }
}
