package com.taarifu.identity.api.controller;

import com.taarifu.common.api.ResponseFactory;
import com.taarifu.common.api.dto.ApiResponse;
import com.taarifu.common.security.RequiresTier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A minimal T3-gated endpoint that exercises the live-tier interceptor (MF-2, AUTH-DESIGN §7.2).
 *
 * <p>Responsibility: proves the {@code @RequiresTier} enforcement path end-to-end and gives the
 * regression test a target. It is intentionally trivial — a binding civic action (sign petition, rate
 * MP, binding poll) is the real T3 surface and lands in {@code engagement}/{@code accountability}; this
 * endpoint stands in so the interceptor + the "forged claim is ignored" guarantee ship in this PR (the
 * MF-2 same-PR landing rule).</p>
 *
 * <p>WHY it is here (not a test fixture): the interceptor must be wired against a real Spring MVC handler
 * to prove the live DB tier — not the token claim — governs. A user presenting an access token whose
 * {@code trustTier} claim says {@code T3} but whose live DB tier is below T3 is still blocked.</p>
 */
@RestController
@RequestMapping("/demo")
public class DemoTierController {

    private final ResponseFactory responses;

    /** @param responses envelope builder. */
    public DemoTierController(ResponseFactory responses) {
        this.responses = responses;
    }

    /**
     * A no-op action gated at T3 — reachable only when the caller's <b>live</b> tier is T3.
     *
     * @return {@code 200} on success; the {@code RequiresTierAspect} throws {@code TIER_TOO_LOW} (403)
     *         when the live tier is below T3, regardless of the token's {@code trustTier} claim.
     */
    @GetMapping("/t3-action")
    @PreAuthorize("isAuthenticated()")
    @RequiresTier("T3")
    public ResponseEntity<ApiResponse<Map<String, String>>> t3Action() {
        return ResponseEntity.ok(responses.ok(Map.of("status", "allowed")));
    }
}
