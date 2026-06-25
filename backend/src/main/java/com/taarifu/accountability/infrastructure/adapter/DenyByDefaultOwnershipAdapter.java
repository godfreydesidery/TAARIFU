package com.taarifu.accountability.infrastructure.adapter;

import com.taarifu.accountability.domain.port.RepresentativeOwnershipPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * The <b>fail-safe fallback</b> {@link RepresentativeOwnershipPort} for the right-of-reply ownership fence
 * (PRD &sect;10 Epic M6, US-6.2; CLAUDE.md &sect;3 "fail safe &amp; deny-by-default").
 *
 * <p>Responsibility: keep the ownership fence <b>closed</b> if — and only if — no real adapter is present. It
 * answers {@code false} for every account, so with no real adapter NO representative-self reply is ever
 * accepted. The right-of-reply feature still works end-to-end via the <b>curated on-behalf</b> path
 * ({@code ADMIN}/{@code ROOT}), which does not consult this port.</p>
 *
 * <p><b>Status (now wired):</b> the real {@link com.taarifu.accountability.infrastructure.adapter.InstitutionsBackedOwnershipAdapter}
 * is present as an unconditional {@code @Component} and is the <b>live default</b> — it resolves ownership via
 * institutions' published {@code RepresentativeQueryApi.ownsRepresentative}. So in the running application this
 * stub is <b>suppressed</b> by its {@code @ConditionalOnMissingBean} and the self-reply path is genuinely
 * available (a rated rep can reply to their own rating, never a rival's). This stub remains as the documented
 * deny-by-default backstop should the real adapter ever be removed.</p>
 *
 * <p><b>WHY a stub that denies (not one that allows):</b> a permissive default would be a security hole — any
 * authenticated user could post as any representative. Deny-by-default means the worst case of a missing real
 * adapter is "the self-reply path is temporarily unavailable", never "anyone can speak as a representative"
 * (deny-by-default authz, CLAUDE.md &sect;12).</p>
 *
 * <p><b>WHY {@code @ConditionalOnMissingBean}:</b> the real institutions-backed adapter (a plain
 * {@code @Component}) registers a {@link RepresentativeOwnershipPort} bean, so Spring drops this stub and uses
 * the real one, with <b>no change to this class</b>. Without the real adapter the stub guarantees the app boots
 * and tests run unchanged with the fence safely closed.</p>
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
