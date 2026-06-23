package com.taarifu.institutions.application.mapper;

import com.taarifu.geography.domain.model.Constituency;
import com.taarifu.geography.domain.model.Location;
import com.taarifu.institutions.api.dto.ParliamentDto;
import com.taarifu.institutions.api.dto.ParliamentRoleDto;
import com.taarifu.institutions.api.dto.PoliticalPartyDto;
import com.taarifu.institutions.api.dto.RepresentativeDto;
import com.taarifu.institutions.api.dto.RepresentativeSummaryDto;
import com.taarifu.institutions.domain.model.Parliament;
import com.taarifu.institutions.domain.model.ParliamentRole;
import com.taarifu.institutions.domain.model.PoliticalParty;
import com.taarifu.institutions.domain.model.Representative;
import org.springframework.stereotype.Component;

/**
 * Maps institutions entities to their boundary DTOs (ARCHITECTURE.md §3.3, CLAUDE.md §8).
 *
 * <p>Responsibility: the single translation layer from {@link PoliticalParty}/{@link Parliament}/
 * {@link ParliamentRole}/{@link Representative} entities to the {@code api.dto} records, so
 * <b>entities never leave the module</b> (CLAUDE.md §8) and only the {@code publicId} is exposed, never
 * the internal {@code Long id} (ADR-0006).</p>
 *
 * <p>WHY a hand-written {@code @Component} mapper (not MapStruct): the mappings are simple and benefit
 * from explicit, documented null-handling of optional links (a special-seats MP has no constituency; an
 * independent has no party). All linked-entity access is null-safe. This mirrors the geography mapper's
 * choice (ARCHITECTURE.md §2) — later modules may adopt MapStruct.</p>
 */
@Component
public class InstitutionsMapper {

    /**
     * @param party the party entity.
     * @return the party DTO.
     */
    public PoliticalPartyDto toPartyDto(PoliticalParty party) {
        return new PoliticalPartyDto(
                party.getPublicId(),
                party.getCode(),
                party.getName(),
                party.getAbbreviation(),
                party.getIdeology(),
                party.getFoundedYear(),
                party.getLogoRef(),
                party.getStatus().name(),
                party.getContacts());
    }

    /**
     * @param parliament the parliament term entity.
     * @return the parliament DTO.
     */
    public ParliamentDto toParliamentDto(Parliament parliament) {
        return new ParliamentDto(
                parliament.getPublicId(),
                parliament.getTermNumber(),
                parliament.getName(),
                parliament.getLegislature().name(),
                parliament.getStartDate(),
                parliament.getEndDate(),
                parliament.isCurrent());
    }

    /**
     * @param role the parliament-role entity.
     * @return the parliament-role DTO.
     */
    public ParliamentRoleDto toParliamentRoleDto(ParliamentRole role) {
        return new ParliamentRoleDto(
                role.getPublicId(),
                role.getCode(),
                role.getName(),
                role.getDescription());
    }

    /**
     * Maps a representative to the lean card DTO used by find-my-rep and directory lists.
     *
     * @param rep the representative entity.
     * @return the lean summary DTO with denormalised party/geography names.
     */
    public RepresentativeSummaryDto toSummaryDto(Representative rep) {
        PoliticalParty party = rep.getParty();
        Constituency constituency = rep.getConstituency();
        Location ward = rep.getWard();
        return new RepresentativeSummaryDto(
                rep.getPublicId(),
                rep.getProfileId(),
                rep.getType().name(),
                rep.getMandate().name(),
                rep.getStatus().name(),
                party != null ? party.getName() : null,
                party != null ? party.getAbbreviation() : null,
                constituency != null ? constituency.getPublicId() : null,
                constituency != null ? constituency.getName() : null,
                ward != null ? ward.getPublicId() : null,
                ward != null ? ward.getName() : null,
                rep.getLegislature().name());
    }

    /**
     * Maps a representative to the full profile DTO.
     *
     * @param rep the representative entity.
     * @return the full profile DTO with denormalised reference names.
     */
    public RepresentativeDto toDto(Representative rep) {
        PoliticalParty party = rep.getParty();
        Constituency constituency = rep.getConstituency();
        Location ward = rep.getWard();
        Parliament parliament = rep.getParliament();
        ParliamentRole role = rep.getParliamentRole();
        return new RepresentativeDto(
                rep.getPublicId(),
                rep.getProfileId(),
                rep.getType().name(),
                rep.getMandate().name(),
                rep.getStatus().name(),
                constituency != null ? constituency.getPublicId() : null,
                constituency != null ? constituency.getName() : null,
                ward != null ? ward.getPublicId() : null,
                ward != null ? ward.getName() : null,
                party != null ? party.getPublicId() : null,
                party != null ? party.getName() : null,
                party != null ? party.getAbbreviation() : null,
                rep.getLegislature().name(),
                parliament != null ? parliament.getPublicId() : null,
                parliament != null ? parliament.getName() : null,
                role != null ? role.getPublicId() : null,
                role != null ? role.getName() : null,
                rep.getElectedAt(),
                rep.getBio());
    }
}
