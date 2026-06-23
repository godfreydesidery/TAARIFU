package com.taarifu.responders;

import com.taarifu.responders.api.dto.ResponderDto;
import com.taarifu.responders.application.mapper.ResponderMapper;
import com.taarifu.responders.domain.model.Organisation;
import com.taarifu.responders.domain.model.Responder;
import com.taarifu.responders.domain.model.enums.CoverageType;
import com.taarifu.responders.domain.model.enums.OrganisationStatus;
import com.taarifu.responders.domain.model.enums.OrganisationType;
import com.taarifu.responders.domain.model.enums.ResponderType;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the responder domain model + mapper (PRD §24.1/§24.4) — no Spring, no Docker.
 *
 * <p>Responsibility: pins two behaviours that gate citizen-facing visibility and routing: the
 * "publicly listable = ACTIVE + verified" rule (§24.4), and area-coverage matching including the
 * nationwide short-circuit (§24.1). It also verifies the mapper never leaks internal ids and copies the
 * id-reference sets faithfully.</p>
 */
class ResponderDomainTest {

    @Test
    void organisation_isPubliclyListable_onlyWhenActiveAndVerified() {
        Organisation org = Organisation.create("TANESCO", OrganisationType.PARASTATAL);
        // New org starts PENDING + unverified → not listable (§24.4).
        assertThat(org.isPubliclyListable()).isFalse();

        org.changeStatus(OrganisationStatus.ACTIVE);
        assertThat(org.isPubliclyListable()).isFalse(); // active but unverified

        org.setVerified(true);
        assertThat(org.isPubliclyListable()).isTrue();   // active + verified

        org.changeStatus(OrganisationStatus.SUSPENDED);
        assertThat(org.isPubliclyListable()).isFalse();  // verified but suspended
    }

    @Test
    void responder_coversArea_membershipAndNationwide() {
        Organisation org = Organisation.create("TANESCO", OrganisationType.PARASTATAL);
        UUID ward = UUID.randomUUID();
        UUID otherWard = UUID.randomUUID();

        Responder areaScoped = Responder.create(org, "TANESCO — Region",
                ResponderType.UTILITY, CoverageType.AREAS);
        areaScoped.setCoverageAreaIds(Set.of(ward));
        assertThat(areaScoped.coversArea(ward)).isTrue();
        assertThat(areaScoped.coversArea(otherWard)).isFalse();

        Responder nationwide = Responder.create(org, "TANESCO — National",
                ResponderType.UTILITY, CoverageType.NATIONWIDE);
        // Nationwide covers any area regardless of the (ignored) area set.
        assertThat(nationwide.coversArea(otherWard)).isTrue();
    }

    @Test
    void mapper_exposesPublicIdsOnly_andCopiesCategorySet() {
        Organisation org = Organisation.create("DAWASA", OrganisationType.PARASTATAL);
        Responder responder = Responder.create(org, "DAWASA — Water",
                ResponderType.UTILITY, CoverageType.AREAS);
        UUID cat = UUID.randomUUID();
        responder.setHandledCategoryIds(Set.of(cat));

        ResponderDto dto = new ResponderMapper().toResponderDto(responder);

        assertThat(dto.id()).isEqualTo(responder.getPublicId());
        assertThat(dto.organisationName()).isEqualTo("DAWASA");
        assertThat(dto.responderType()).isEqualTo("UTILITY");
        assertThat(dto.coverageType()).isEqualTo("AREAS");
        assertThat(dto.handledCategoryIds()).containsExactly(cat);
    }
}
