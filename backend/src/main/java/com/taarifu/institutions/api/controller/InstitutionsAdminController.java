package com.taarifu.institutions.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.institutions.api.dto.ParliamentDto;
import com.taarifu.institutions.api.dto.ParliamentRoleDto;
import com.taarifu.institutions.api.dto.ParliamentRoleWriteDto;
import com.taarifu.institutions.api.dto.ParliamentWriteDto;
import com.taarifu.institutions.api.dto.PartyWriteDto;
import com.taarifu.institutions.api.dto.PoliticalPartyDto;
import com.taarifu.institutions.api.dto.RepresentativeDto;
import com.taarifu.institutions.api.dto.RepresentativeWriteDto;
import com.taarifu.institutions.application.service.InstitutionsAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin-only REST surface for institutions reference data — CRUD of political parties, parliament terms,
 * parliament roles, and representatives (PRD §9.1; UC-B11..B13, UC-C04, UC-C08).
 *
 * <p>Responsibility: the thin HTTP layer for admin writes; it validates the request body ({@code @Valid}),
 * delegates to {@link InstitutionsAdminService} (which owns the transaction boundary and every integrity
 * invariant), and wraps results in the single {@link ApiResponse} envelope. No business logic, no
 * {@code @Transactional} (CLAUDE.md §8).</p>
 *
 * <p><b>Authorization (deny-by-default, method-level).</b> WHY {@code @PreAuthorize("hasRole('ADMIN')")}
 * at the class level rather than relying on URL config: reference-data mutation is a privileged surface;
 * the legacy gap was "authenticated-only" admin endpoints (PRD §7.1). Method security makes every write
 * an explicit ADMIN-gated decision in code (ARCHITECTURE.md §6.2). These paths are <b>not</b> in any
 * public allow-list, so an unauthenticated caller is rejected by the filter chain before method security
 * even runs.</p>
 */
@RestController
@RequestMapping(path = "/admin/institutions")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Institutions Admin", description = "Admin CRUD for parties, parliaments, roles, and representatives (ROLE_ADMIN).")
public class InstitutionsAdminController {

    private final InstitutionsAdminService adminService;
    private final ResponseFactory responses;

    /**
     * @param adminService institutions write service.
     * @param responses    envelope builder.
     */
    public InstitutionsAdminController(InstitutionsAdminService adminService, ResponseFactory responses) {
        this.adminService = adminService;
        this.responses = responses;
    }

    // ---- Political party (UC-B11) ----

    /**
     * Creates a political party.
     *
     * @param dto the validated party payload.
     * @return an envelope carrying the created {@link PoliticalPartyDto}.
     */
    @PostMapping("/parties")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a political party")
    public ApiResponse<PoliticalPartyDto> createParty(@Valid @RequestBody PartyWriteDto dto) {
        return responses.ok(adminService.createParty(dto));
    }

    /**
     * Updates a political party (code immutable).
     *
     * @param partyId the party's public id.
     * @param dto     the validated party payload.
     * @return an envelope carrying the updated {@link PoliticalPartyDto}.
     */
    @PutMapping("/parties/{partyId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a political party")
    public ApiResponse<PoliticalPartyDto> updateParty(@PathVariable UUID partyId,
                                                      @Valid @RequestBody PartyWriteDto dto) {
        return responses.ok(adminService.updateParty(partyId, dto));
    }

    /**
     * Soft-deletes a political party.
     *
     * @param partyId the party's public id.
     * @return an empty success envelope.
     */
    @DeleteMapping("/parties/{partyId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a political party")
    public ApiResponse<Void> deleteParty(@PathVariable UUID partyId) {
        adminService.deleteParty(partyId);
        return responses.ok(null);
    }

    // ---- Parliament (UC-B12) ----

    /**
     * Creates a parliament term.
     *
     * @param dto the validated parliament payload.
     * @return an envelope carrying the created {@link ParliamentDto}.
     */
    @PostMapping("/parliaments")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a parliament term")
    public ApiResponse<ParliamentDto> createParliament(@Valid @RequestBody ParliamentWriteDto dto) {
        return responses.ok(adminService.createParliament(dto));
    }

    /**
     * Updates a parliament term.
     *
     * @param parliamentId the term's public id.
     * @param dto          the validated parliament payload.
     * @return an envelope carrying the updated {@link ParliamentDto}.
     */
    @PutMapping("/parliaments/{parliamentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a parliament term")
    public ApiResponse<ParliamentDto> updateParliament(@PathVariable UUID parliamentId,
                                                       @Valid @RequestBody ParliamentWriteDto dto) {
        return responses.ok(adminService.updateParliament(parliamentId, dto));
    }

    /**
     * Soft-deletes a parliament term.
     *
     * @param parliamentId the term's public id.
     * @return an empty success envelope.
     */
    @DeleteMapping("/parliaments/{parliamentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a parliament term")
    public ApiResponse<Void> deleteParliament(@PathVariable UUID parliamentId) {
        adminService.deleteParliament(parliamentId);
        return responses.ok(null);
    }

    // ---- Parliament role (UC-B13) ----

    /**
     * Creates a parliament role.
     *
     * @param dto the validated role payload.
     * @return an envelope carrying the created {@link ParliamentRoleDto}.
     */
    @PostMapping("/parliament-roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a parliament role")
    public ApiResponse<ParliamentRoleDto> createParliamentRole(
            @Valid @RequestBody ParliamentRoleWriteDto dto) {
        return responses.ok(adminService.createParliamentRole(dto));
    }

    /**
     * Updates a parliament role (code immutable).
     *
     * @param roleId the role's public id.
     * @param dto    the validated role payload.
     * @return an envelope carrying the updated {@link ParliamentRoleDto}.
     */
    @PutMapping("/parliament-roles/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a parliament role")
    public ApiResponse<ParliamentRoleDto> updateParliamentRole(@PathVariable UUID roleId,
                                                               @Valid @RequestBody ParliamentRoleWriteDto dto) {
        return responses.ok(adminService.updateParliamentRole(roleId, dto));
    }

    /**
     * Soft-deletes a parliament role.
     *
     * @param roleId the role's public id.
     * @return an empty success envelope.
     */
    @DeleteMapping("/parliament-roles/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a parliament role")
    public ApiResponse<Void> deleteParliamentRole(@PathVariable UUID roleId) {
        adminService.deleteParliamentRole(roleId);
        return responses.ok(null);
    }

    // ---- Representative (UC-C04, UC-C08) ----

    /**
     * Creates/links a representative (enforces mandate⇄geography + one-SITTING-MP invariants).
     *
     * @param dto the validated representative payload.
     * @return an envelope carrying the created {@link RepresentativeDto}.
     */
    @PostMapping("/representatives")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create/link a representative")
    public ApiResponse<RepresentativeDto> createRepresentative(
            @Valid @RequestBody RepresentativeWriteDto dto) {
        return responses.ok(adminService.createRepresentative(dto));
    }

    /**
     * Updates a representative, including a status transition (e.g. SITTING→FORMER on term end, UC-C08).
     *
     * @param representativeId the representative's public id.
     * @param dto              the validated representative payload.
     * @return an envelope carrying the updated {@link RepresentativeDto}.
     */
    @PutMapping("/representatives/{representativeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a representative (incl. SITTING→FORMER)")
    public ApiResponse<RepresentativeDto> updateRepresentative(@PathVariable UUID representativeId,
                                                               @Valid @RequestBody RepresentativeWriteDto dto) {
        return responses.ok(adminService.updateRepresentative(representativeId, dto));
    }

    /**
     * Soft-deletes a representative (reserved for erroneous rows; routine removal is SITTING→FORMER).
     *
     * @param representativeId the representative's public id.
     * @return an empty success envelope.
     */
    @DeleteMapping("/representatives/{representativeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Soft-delete a representative")
    public ApiResponse<Void> deleteRepresentative(@PathVariable UUID representativeId) {
        adminService.deleteRepresentative(representativeId);
        return responses.ok(null);
    }
}
