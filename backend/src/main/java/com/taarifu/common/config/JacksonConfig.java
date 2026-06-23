package com.taarifu.common.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Jackson serialization policy for the whole API (ARCHITECTURE.md §5.1).
 *
 * <p>Responsibility: pins consistent JSON behaviour so every endpoint's wire format matches the
 * documented contract (PRD §17):</p>
 * <ul>
 *   <li>{@link java.time.Instant} and other temporals serialise as <b>ISO-8601 strings in UTC</b>,
 *       never as numeric epochs — clients (Flutter/Angular) and logs read one stable format.</li>
 *   <li>Unknown properties on inbound DTOs are ignored by Boot's default; we keep camelCase field
 *       names (matching the records) so no surprise renaming occurs.</li>
 * </ul>
 *
 * <p>WHY UTC is forced: a national system with multiple clients must never depend on server-local
 * time; all timestamps in the envelope and DTOs are unambiguous UTC instants (PRD §15).</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * @return a customizer that disables epoch-timestamp serialization and forces UTC.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer taarifuJacksonCustomizer() {
        return builder -> {
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.timeZone(TimeZone.getTimeZone("UTC"));
        };
    }
}
