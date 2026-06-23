package com.taarifu.identity.api.dto;

import java.util.UUID;

/**
 * The result of a successful signup/login: the account id, the server-computed tier hint, and the
 * token pair (AUTH-DESIGN §3, §4).
 *
 * @param userPublicId the authenticated account's public id.
 * @param tier         the server-computed trust tier name (UI hint; gating re-resolves live — MF-2).
 * @param tokens       the issued access + refresh pair.
 */
public record AuthResultDto(UUID userPublicId, String tier, TokenPairDto tokens) {
}
