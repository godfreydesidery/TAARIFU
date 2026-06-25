package com.taarifu.accountability.infrastructure.adapter;

import com.taarifu.accountability.domain.port.RepresentativeOwnershipPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The <b>default deny-stub</b> {@link RepresentativeOwnershipPort} for the right-of-reply ownership fence
 * (PRD &sect;10 Epic M6, US-6.2; CLAUDE.md &sect;3 "fail safe &amp; deny-by-default").
 *
 * <p>Responsibility: keep the ownership fence <b>closed</b> until a deployment wires the real adapter. It
 * answers {@code false} for every account, so until institutions exposes "the linked account of a
 * representative", NO representative-self reply is ever accepted. The right-of-reply feature still works
 * end-to-end today via the <b>curated on-behalf</b> path ({@code ADMIN}/{@code ROOT}), which does not consult
 * this port.</p>
 *
 * <p><b>WHY a stub that denies (not one that allows):</b> a permissive default would be a security hole — any
 * authenticated user could post as any representative. Deny-by-default means the worst case of a missing real
 * adapter is "the self-reply path is temporarily unavailable", never "anyone can speak as a representative"
 * (deny-by-default authz, CLAUDE.md &sect;12).</p>
 *
 * <p><b>WHY {@code @ConditionalOnMissingBean}:</b> when the real institutions-backed adapter is registered
 * (the documented CENTRAL NEED), Spring drops this stub and uses the real one, with <b>no change to the
 * accountability module</b>. Until then the stub guarantees the app boots and tests run unchanged (the
 * "existing stub stays the default" rule).</p>
 */
@Configuration
public class DenyByDefaultOwnershipAdapter {

    /**
     * Registers the deny-by-default ownership port unless a real adapter is already present.
     *
     * @return a {@link RepresentativeOwnershipPort} that denies every self-reply (fail-safe).
     */
    @Bean
    @ConditionalOnMissingBean(RepresentativeOwnershipPort.class)
    public RepresentativeOwnershipPort denyByDefaultRepresentativeOwnershipPort() {
        // Always false: no account is ever resolved as a representative's linked account by the stub, so the
        // self-reply path stays closed until the real institutions-backed adapter is wired (CENTRAL NEED).
        return (UUID accountPublicId, UUID representativePublicId) -> false;
    }
}
