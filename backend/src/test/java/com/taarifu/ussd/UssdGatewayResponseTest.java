package com.taarifu.ussd;

import com.taarifu.ussd.api.dto.UssdGatewayResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UssdGatewayResponse} — the CON/END wire contract the aggregator depends on
 * (PRD §14, EI-4).
 *
 * <p>WHY this is worth a dedicated test: the aggregator renders the response body verbatim and keys off the
 * leading {@code CON }/{@code END } verb to decide whether to keep the session open. A regression in the
 * prefix would silently break every USSD dialogue, so the exact string is pinned here.</p>
 */
class UssdGatewayResponseTest {

    @Test
    void con_rendersContinuePrefix() {
        assertThat(UssdGatewayResponse.con("Chagua aina:").render()).isEqualTo("CON Chagua aina:");
        assertThat(UssdGatewayResponse.con("x").terminal()).isFalse();
    }

    @Test
    void end_rendersTerminatePrefix() {
        assertThat(UssdGatewayResponse.end("Imetumwa. Tikiti: TAR-2026-000001.").render())
                .isEqualTo("END Imetumwa. Tikiti: TAR-2026-000001.");
        assertThat(UssdGatewayResponse.end("x").terminal()).isTrue();
    }
}
