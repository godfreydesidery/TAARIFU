package com.taarifu.reporting.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.reporting.api.dto.CreateIssueCategoryDto;
import com.taarifu.reporting.api.dto.IssueCategoryDto;
import com.taarifu.reporting.api.dto.UpdateIssueCategoryDto;
import com.taarifu.reporting.application.service.IssueCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for the issue-category taxonomy — the citizen-facing picker read and the Admin CRUD
 * (PRD §9.1, Appendix D; UC-B14).
 *
 * <p>Responsibility: thin HTTP layer over {@link IssueCategoryService}. The active-category list is
 * needed by every citizen to file a report, so {@code GET} reads are <b>public</b>
 * ({@code permitAll()} — registered as a public path centrally). All writes are gated
 * {@code @PreAuthorize("hasRole('ADMIN')")} (deny-by-default). No business logic, no transactions
 * (CLAUDE.md §8).</p>
 */
@RestController
@RequestMapping("/issue-categories")
@Tag(name = "Issue Categories", description = "Reportable-issue taxonomy: public picker reads + Admin CRUD.")
public class IssueCategoryController {

    private final IssueCategoryService categoryService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param categoryService category reads/CRUD.
     * @param responses       envelope builder.
     * @param pageRequests    bounded pageable factory.
     * @param pageMapper      page→meta mapper.
     */
    public IssueCategoryController(IssueCategoryService categoryService, ResponseFactory responses,
                                  PageRequestFactory pageRequests, PageMapper pageMapper) {
        this.categoryService = categoryService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists active categories for the picker (public).
     *
     * @param page zero-based page index.
     * @param size page size (capped server-side).
     * @param sort {@code field,asc|desc}.
     * @return a paged envelope of active {@link IssueCategoryDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List active issue categories (picker)")
    public ApiResponse<java.util.List<IssueCategoryDto>> listActive(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<IssueCategoryDto> result = categoryService.listActive(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches one category (public).
     *
     * @param categoryId the category's public id.
     * @return an envelope with the {@link IssueCategoryDto}.
     */
    @GetMapping("/{categoryId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get an issue category")
    public ApiResponse<IssueCategoryDto> get(@PathVariable UUID categoryId) {
        return responses.ok(categoryService.get(categoryId));
    }

    /**
     * Lists all categories (active + retired) for the Admin console.
     *
     * @param page zero-based page index.
     * @param size page size.
     * @param sort sort expression.
     * @return a paged envelope of all {@link IssueCategoryDto}.
     */
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] List all issue categories")
    public ApiResponse<java.util.List<IssueCategoryDto>> listAll(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<IssueCategoryDto> result = categoryService.listAll(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Creates a category (Admin).
     *
     * @param request the validated create request.
     * @return {@code 201} + the created {@link IssueCategoryDto}.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Create an issue category")
    public ResponseEntity<ApiResponse<IssueCategoryDto>> create(
            @Valid @RequestBody CreateIssueCategoryDto request) {
        IssueCategoryDto created = categoryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.ok(created));
    }

    /**
     * Edits a category (Admin).
     *
     * @param categoryId the category's public id.
     * @param request    the validated update request.
     * @return {@code 200} + the updated {@link IssueCategoryDto}.
     */
    @PutMapping("/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Update an issue category")
    public ApiResponse<IssueCategoryDto> update(@PathVariable UUID categoryId,
                                                @Valid @RequestBody UpdateIssueCategoryDto request) {
        return responses.ok(categoryService.update(categoryId, request));
    }

    /**
     * Soft-deletes (retires) a category (Admin).
     *
     * @param categoryId the category's public id.
     * @return {@code 200} + no payload.
     */
    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Soft-delete an issue category")
    public ApiResponse<Void> delete(@PathVariable UUID categoryId) {
        categoryService.delete(categoryId, CurrentUser.requirePublicId());
        return responses.ok(null);
    }
}
