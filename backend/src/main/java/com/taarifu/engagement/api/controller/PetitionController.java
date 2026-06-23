package com.taarifu.engagement.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.pagination.PageMapper;
import com.taarifu.common.pagination.PageRequestFactory;
import com.taarifu.common.security.CurrentUser;
import com.taarifu.common.security.RequiresTier;
import com.taarifu.engagement.api.dto.CreatePetitionRequest;
import com.taarifu.engagement.api.dto.PetitionDto;
import com.taarifu.engagement.api.dto.SignPetitionRequest;
import com.taarifu.engagement.application.service.PetitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for petitions (PRD §12.2 M9; ARCHITECTURE.md §3.3, §6.2).
 *
 * <p>Responsibility: the thin HTTP layer for {@code /petitions}. It validates input, delegates to
 * {@link PetitionService}, and wraps results in the single {@link ApiResponse} envelope. No business
 * logic, no {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization design (deny-by-default, method-level — ARCHITECTURE §6.2):</b></p>
 * <ul>
 *   <li><b>List/view</b> — {@code permitAll()}: published petitions are public reference (PRD §22.6). The
 *       service filters out DRAFT/moderation-held rows so {@code permitAll} never exposes a draft.</li>
 *   <li><b>Create</b> — {@code isAuthenticated()}: any signed-in citizen/org may author (moderation gates
 *       public visibility, not authoring).</li>
 *   <li><b>Sign</b> — the <b>binding civic act</b>: {@code @RequiresTier("T3")} (live-resolved, MF-2) is
 *       the tier half of the integrity fence (D18, PRD §23.5). The service applies the no-self-petition +
 *       one-per-person halves; <b>token balance never appears in this path</b>.</li>
 * </ul>
 *
 * <p>WHY the public list path is registered centrally: {@code GET /petitions} is a public read and must
 * be added to the security allow-list — flagged under CENTRAL INTEGRATION NEEDS (this module must not edit
 * SecurityConfig). Until then, {@code @PreAuthorize("permitAll()")} permits the handler but the URL filter
 * still requires the central registration to be reachable unauthenticated.</p>
 */
@RestController
@RequestMapping("/petitions")
@Tag(name = "Engagement", description = "Petitions, surveys/polls, and public Q&A (Swahili-first).")
public class PetitionController {

    private final PetitionService petitionService;
    private final ResponseFactory responses;
    private final PageRequestFactory pageRequests;
    private final PageMapper pageMapper;

    /**
     * @param petitionService petition use-cases.
     * @param responses       envelope builder.
     * @param pageRequests    safe {@link Pageable} factory (size caps).
     * @param pageMapper      {@code Page}→{@code PageMeta} adapter.
     */
    public PetitionController(PetitionService petitionService,
                              ResponseFactory responses,
                              PageRequestFactory pageRequests,
                              PageMapper pageMapper) {
        this.petitionService = petitionService;
        this.responses = responses;
        this.pageRequests = pageRequests;
        this.pageMapper = pageMapper;
    }

    /**
     * Lists publicly-visible petitions, paged.
     *
     * @param page zero-based page index.
     * @param size page size (capped at 100).
     * @param sort sort expression {@code field,asc|desc}.
     * @return a paged envelope of {@link PetitionDto}.
     */
    @GetMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "List public petitions")
    public ApiResponse<List<PetitionDto>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = pageRequests.of(page, size, sort);
        Page<PetitionDto> result = petitionService.listPublic(pageable);
        return responses.paged(result.getContent(), pageMapper.toMeta(result));
    }

    /**
     * Fetches a single petition by public id.
     *
     * @param petitionId the petition's public id.
     * @return an envelope carrying the {@link PetitionDto}.
     */
    @GetMapping("/{petitionId}")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Get a petition by id")
    public ApiResponse<PetitionDto> get(@PathVariable UUID petitionId) {
        return responses.ok(petitionService.get(petitionId));
    }

    /**
     * Creates a petition (DRAFT; moderation gates public visibility — UC-E01/E02).
     *
     * @param request the petition fields.
     * @return {@code 201} + the created {@link PetitionDto}.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a petition (DRAFT)")
    public ApiResponse<PetitionDto> create(@Valid @RequestBody CreatePetitionRequest request) {
        PetitionDto dto = petitionService.create(
                request.title(), request.body(), request.targetType(), request.targetId(),
                request.signatureGoal(), request.deadline(), CurrentUser.requirePublicId());
        return responses.ok(dto);
    }

    /**
     * Signs a petition — the binding civic act (UC-E03). T3-gated (integrity fence; balance never read).
     *
     * @param petitionId the petition to sign.
     * @param request    the optional comment + privacy choice (signer identity comes from the token).
     * @return an envelope carrying the updated {@link PetitionDto}.
     */
    @PostMapping("/{petitionId}/signatures")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T3")
    @Operation(summary = "Sign a petition (binding, T3, one-per-person)")
    public ApiResponse<PetitionDto> sign(@PathVariable UUID petitionId,
                                         @Valid @RequestBody SignPetitionRequest request) {
        PetitionDto dto = petitionService.sign(
                petitionId, CurrentUser.requirePublicId(),
                request.comment(), request.publicSignature());
        return responses.ok(dto);
    }
}
