package com.taarifu.reporting.application.service;

import com.taarifu.common.error.ApiException;
import com.taarifu.common.error.ErrorCode;
import com.taarifu.common.error.ResourceNotFoundException;
import com.taarifu.reporting.api.dto.CreateIssueCategoryDto;
import com.taarifu.reporting.api.dto.IssueCategoryDto;
import com.taarifu.reporting.api.dto.UpdateIssueCategoryDto;
import com.taarifu.reporting.application.mapper.ReportingMapper;
import com.taarifu.reporting.domain.model.IssueCategory;
import com.taarifu.reporting.domain.model.enums.ReportVisibility;
import com.taarifu.reporting.domain.model.enums.RoutingLevel;
import com.taarifu.reporting.domain.repository.IssueCategoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service for the issue-category taxonomy — reads for the picker, Admin CRUD (UC-B14,
 * PRD §9.1, Appendix D).
 *
 * <p>Responsibility: owns the transaction boundary and the semantic rules around categories — duplicate
 * code rejection, parent existence, and enum parsing of the routing/visibility tokens — returning DTOs
 * (never entities). Controllers stay thin (CLAUDE.md §8).</p>
 *
 * <p>WHY category writes are guarded at the controller with {@code @PreAuthorize("hasRole('ADMIN')")} and
 * not here: method-security gates the HTTP surface deny-by-default; this service trusts it has been called
 * by an authorised admin and focuses on data integrity. The {@code code} is immutable after creation
 * (clients/imports match on it, Appendix D).</p>
 */
@Service
@Transactional(readOnly = true)
public class IssueCategoryService {

    private final IssueCategoryRepository categoryRepository;
    private final ReportingMapper mapper;

    /**
     * @param categoryRepository category persistence port.
     * @param mapper             entity→DTO mapper.
     */
    public IssueCategoryService(IssueCategoryRepository categoryRepository, ReportingMapper mapper) {
        this.categoryRepository = categoryRepository;
        this.mapper = mapper;
    }

    /**
     * Lists active categories for the picker (US-3.1).
     *
     * @param pageable bounded paging/sorting.
     * @return a page of active {@link IssueCategoryDto}.
     */
    public Page<IssueCategoryDto> listActive(Pageable pageable) {
        return categoryRepository.findByActiveTrue(pageable).map(mapper::toIssueCategoryDto);
    }

    /**
     * Lists all categories (active + retired) for the Admin console (UC-B14).
     *
     * @param pageable bounded paging/sorting.
     * @return a page of all {@link IssueCategoryDto}.
     */
    public Page<IssueCategoryDto> listAll(Pageable pageable) {
        return categoryRepository.findAllByDeletedFalse(pageable).map(mapper::toIssueCategoryDto);
    }

    /**
     * Fetches one category by public id.
     *
     * @param publicId the category's public id.
     * @return the {@link IssueCategoryDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    public IssueCategoryDto get(UUID publicId) {
        return mapper.toIssueCategoryDto(requireCategory(publicId));
    }

    /**
     * Creates a category (UC-B14, ROLE_ADMIN). Rejects a duplicate code and a non-existent parent.
     *
     * @param request the validated create request.
     * @return the created {@link IssueCategoryDto}.
     * @throws ApiException {@link ErrorCode#CONFLICT} if the code already exists; {@link ErrorCode#BAD_REQUEST}
     *                      if the routing level / visibility token is not a valid enum value.
     * @throws ResourceNotFoundException if a {@code parentId} is given but no such category exists.
     */
    @Transactional
    public IssueCategoryDto create(CreateIssueCategoryDto request) {
        if (categoryRepository.existsByCode(request.code())) {
            throw new ApiException(ErrorCode.CONFLICT, "reporting.category.duplicateCode", request.code());
        }
        IssueCategory parent = request.parentId() != null ? requireCategory(request.parentId()) : null;
        RoutingLevel routingLevel = parseRoutingLevel(request.defaultRoutingLevel());
        ReportVisibility visibility = parseVisibility(request.defaultVisibility());

        IssueCategory category = new IssueCategory(
                request.code(), request.name(), parent, routingLevel,
                request.defaultSlaTtfrMinutes(), request.defaultSlaTtrMinutes(),
                request.sensitive(), request.forcePrivate(), visibility, request.icon());
        return mapper.toIssueCategoryDto(categoryRepository.save(category));
    }

    /**
     * Edits a category (UC-B14, ROLE_ADMIN). The {@code code} and parent are not editable here.
     *
     * @param publicId the category's public id.
     * @param request  the validated update request.
     * @return the updated {@link IssueCategoryDto}.
     * @throws ResourceNotFoundException if not found/soft-deleted.
     * @throws ApiException {@link ErrorCode#BAD_REQUEST} if a token is not a valid enum value.
     */
    @Transactional
    public IssueCategoryDto update(UUID publicId, UpdateIssueCategoryDto request) {
        IssueCategory category = requireCategory(publicId);
        RoutingLevel routingLevel = parseRoutingLevel(request.defaultRoutingLevel());
        ReportVisibility visibility = parseVisibility(request.defaultVisibility());
        category.applyEdit(request.name(), routingLevel, request.defaultSlaTtfrMinutes(),
                request.defaultSlaTtrMinutes(), request.sensitive(), request.forcePrivate(),
                visibility, request.icon(), request.active());
        return mapper.toIssueCategoryDto(category);
    }

    /**
     * Soft-deletes (retires) a category (UC-B14, ROLE_ADMIN). WHY soft-delete, not physical: existing
     * reports reference the category by FK and must stay intact (PRD §9, §18). A retired category is
     * hidden from the picker; consider {@code active=false} first for a reversible retirement.
     *
     * @param publicId   the category's public id.
     * @param actorPublicId the acting admin's public id (for the audit columns).
     * @throws ResourceNotFoundException if not found/soft-deleted.
     */
    @Transactional
    public void delete(UUID publicId, UUID actorPublicId) {
        IssueCategory category = requireCategory(publicId);
        category.markDeleted(actorPublicId);
    }

    /** Loads a category by public id or throws a localised not-found. */
    private IssueCategory requireCategory(UUID publicId) {
        return categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("reporting.category.notFound", publicId));
    }

    /** Parses a routing-level token to its enum, or throws a typed bad-request. */
    private RoutingLevel parseRoutingLevel(String token) {
        try {
            return RoutingLevel.valueOf(token);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "reporting.category.badRoutingLevel", token);
        }
    }

    /** Parses a visibility token to its enum, or throws a typed bad-request. */
    private ReportVisibility parseVisibility(String token) {
        try {
            return ReportVisibility.valueOf(token);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "reporting.category.badVisibility", token);
        }
    }
}
