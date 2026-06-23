package com.taarifu.common.infrastructure.persistence;

import com.taarifu.common.domain.port.CryptoPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bridges the Spring-managed {@link CryptoPort} bean into JPA {@code AttributeConverter}s, which
 * Hibernate instantiates outside the Spring container (ARCHITECTURE.md §4.3, PRD §18).
 *
 * <p>Responsibility: Hibernate creates converter instances itself, so they cannot use constructor
 * injection. This component captures the {@link CryptoPort} bean into a {@code static} reference at
 * startup so {@link EncryptedStringConverter} (a plain JPA converter) can reach it. This is the
 * standard, contained pattern for injecting a service into a JPA converter.</p>
 *
 * <p>WHY a static bridge (an exception to "constructor injection only"): there is no other way to give
 * a Hibernate-managed converter access to a Spring bean; the static is written exactly once during
 * context init and read-only thereafter, so it is safe and localised to this persistence concern.</p>
 */
@Component
public class CryptoConverterSupport {

    private static CryptoPort cryptoPort;

    /**
     * @param cryptoPort the encryption port bean, captured statically for converter use.
     */
    @Autowired
    public CryptoConverterSupport(CryptoPort cryptoPort) {
        CryptoConverterSupport.cryptoPort = cryptoPort;
    }

    /**
     * @return the captured {@link CryptoPort}.
     * @throws IllegalStateException if accessed before the Spring context initialised it (a coding/
     *         lifecycle error — converters must only run once persistence is up).
     */
    static CryptoPort cryptoPort() {
        if (cryptoPort == null) {
            throw new IllegalStateException("CryptoPort not yet initialised for JPA converters");
        }
        return cryptoPort;
    }
}
