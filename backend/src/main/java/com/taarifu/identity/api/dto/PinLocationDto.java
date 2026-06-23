package com.taarifu.identity.api.dto;

import com.taarifu.identity.domain.model.enums.AssociationType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request to pin a ward location to the caller's profile (AUTH-DESIGN §6, D12).
 *
 * <p>The pin is PRIVATE PII; it is never echoed publicly. Pinning at least one location is the
 * ≥1-location half of the T2 predicate.</p>
 *
 * @param wardPublicId    the ward to pin (minimum pin granularity, PRD §9.0).
 * @param associationType how the profile relates to the place.
 * @param primary         whether this becomes the single primary (default-context) location.
 */
public record PinLocationDto(
        @NotNull(message = "identity.ward.required") UUID wardPublicId,
        @NotNull(message = "identity.associationType.required") AssociationType associationType,
        boolean primary
) {
}
