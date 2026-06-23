package com.taarifu.reporting.infrastructure.adapter;

import com.taarifu.geography.application.service.GeographyQueryService;
import com.taarifu.reporting.domain.port.WardResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The production {@link WardResolver} adapter — delegates to geography's public query service
 * (ARCHITECTURE.md §3.2, §4.3).
 *
 * <p>Responsibility: implements reporting's {@link WardResolver} port by calling
 * {@link GeographyQueryService#resolveWardPin(UUID)}, which validates the id is a WARD (minimum pin
 * granularity, PRD §9.0) and resolves the constituency in effect through geography's own effective-dated
 * bridge. This is the single seam where reporting touches geography — through its <b>service</b>, never
 * its repositories/tables (the documented cross-module rule, ARCHITECTURE.md §4.3).</p>
 *
 * <p>WHY an adapter in infrastructure (not the geography call inline in the reporting service): keeps the
 * application service depending on the abstraction so it unit-tests with a stub, and localises the one
 * upstream-module coupling to one replaceable class (SOLID/DIP, CLAUDE.md §3).</p>
 */
@Component
public class GeographyWardResolver implements WardResolver {

    private final GeographyQueryService geographyQueryService;

    /**
     * @param geographyQueryService geography's public read service (owns ward→constituency resolution).
     */
    public GeographyWardResolver(GeographyQueryService geographyQueryService) {
        this.geographyQueryService = geographyQueryService;
    }

    /** {@inheritDoc} */
    @Override
    public Resolution resolveWard(UUID wardPublicId) {
        GeographyQueryService.WardPin pin = geographyQueryService.resolveWardPin(wardPublicId);
        UUID constituencyId = pin.constituency() != null ? pin.constituency().getPublicId() : null;
        return new Resolution(pin.ward().getPublicId(), constituencyId);
    }
}
