package com.taarifu.admin.infrastructure.config;

import com.taarifu.admin.api.spi.IdentityAdminPort;
import com.taarifu.admin.infrastructure.adapter.StubIdentityAdminAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Module wiring for {@code admin} (ARCHITECTURE §3.3 {@code infrastructure.config}).
 *
 * <p>Responsibility: provide the fallback {@link IdentityAdminPort} stub so the admin module is
 * self-sufficient until the {@code identity} module registers its real adapter. {@code @ConditionalOnMissingBean}
 * means the real identity-backed bean — once it exists — wins, and this stub is never created (it cannot
 * shadow production). The {@code List<ModuleStatsProvider>} the dashboard consumes needs no bean here:
 * Spring injects an empty list when no module has published a provider yet, and the real provider beans
 * register themselves in their own modules.</p>
 */
@Configuration
public class AdminModuleConfig {

    /**
     * @return a no-op {@link IdentityAdminPort} used only when no real implementation is on the context
     *         (pre-wiring / isolated tests). Production supplies the identity-backed adapter, which takes
     *         precedence.
     */
    @Bean
    @ConditionalOnMissingBean(IdentityAdminPort.class)
    public IdentityAdminPort stubIdentityAdminPort() {
        return new StubIdentityAdminAdapter();
    }
}
