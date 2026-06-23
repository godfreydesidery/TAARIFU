package com.taarifu.identity.api.dto;

import java.util.UUID;

/**
 * Response after adding a {@code ProfileLocation} (VERIFICATION-DESIGN §6). The pin itself is private
 * PII and is never echoed; only its public id + the caller's resulting live tier are returned.
 *
 * @param locationPublicId the new location's public id (for later set-primary/set-electoral/remove).
 * @param tier             the caller's recomputed live trust tier (a pin may complete the T2 predicate).
 */
public record LocationCreatedDto(UUID locationPublicId, String tier) {
}
